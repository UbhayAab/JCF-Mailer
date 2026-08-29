package com.jarurat.mailer.security;

import com.jarurat.mailer.models.AuditLog;
import com.jarurat.mailer.models.User;
import com.jarurat.mailer.repositories.AuditLogRepository;
import com.jarurat.mailer.repositories.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the console lockout counter is charged for, and what it is allowed to close.
 *
 * Both halves are behaviour nobody sees until somebody cannot get in. If the counter
 * stops advancing, the console silently loses its only defence against password
 * guessing and the login page looks exactly the same; if the counter reaches too far,
 * a person who fumbled a password loses their mailbox and there is nothing on the
 * screen to say why. Those are the two directions this file pins down.
 */
class LoginAttemptListenerTest {

    private static final String ADDRESS = "priya@jarurat.care";

    private final UserRepository users = mock(UserRepository.class);
    private final AuditLogRepository audit = mock(AuditLogRepository.class);
    private final LoginAttemptListener listener = new LoginAttemptListener(users, audit);

    @Test
    @DisplayName("five failures still lock the console account, even though the mail server threw last")
    void consoleLockoutStillWorksWhenTheMailboxProviderProducedTheException() {
        // This is the regression that matters most in this file. With two providers
        // on one form the exception ProviderManager publishes is almost always the
        // mailbox provider's, because the DAO provider throws first and is overwritten.
        // Routing every marked failure away from app_user therefore reads like a
        // separation of concerns and is in fact a complete disabling of the console
        // lockout: after it, nothing would ever increment this counter again.
        User user = consoleUser();
        when(users.findByEmail(ADDRESS)).thenReturn(Optional.of(user));

        for (int i = 0; i < 5; i++) listener.onFailure(mailboxRefusedIt());

        assertThat(user.isLocked()).isTrue();
        assertThat(actions()).contains("ACCOUNT_LOCKED");
    }

    @Test
    @DisplayName("an already-locked account is not counted again, so knocking cannot extend the lock")
    void aLockedAccountIsNotChargedFurther() {
        // A locked account no longer short circuits the manager, so its attempts now
        // come back here as ordinary bad-credentials events. Counting them would let
        // an attacker hold somebody locked out for as long as they cared to keep
        // sending requests.
        User user = consoleUser();
        user.setLockedUntil(LocalDateTime.now().plusMinutes(15));
        user.setFailedLoginAttempts(0);
        when(users.findByEmail(ADDRESS)).thenReturn(Optional.of(user));

        for (int i = 0; i < 20; i++) listener.onFailure(mailboxRefusedIt());

        assertThat(user.getFailedLoginAttempts()).isZero();
        verify(users, never()).save(any(User.class));
    }

    @Test
    @DisplayName("a failure for an address with no console account writes an audit row and nothing else")
    void mailboxOnlyFailureTouchesNoUserRow() {
        when(users.findByEmail(ADDRESS)).thenReturn(Optional.empty());

        listener.onFailure(mailboxRefusedIt());

        verify(users, never()).save(any(User.class));
        assertThat(actions()).containsExactly("LOGIN_FAILED");
    }

    @Test
    @DisplayName("the audit row says which store refused the password")
    void theAuditRowNamesTheStore() {
        when(users.findByEmail(ADDRESS)).thenReturn(Optional.empty());

        listener.onFailure(mailboxRefusedIt());
        listener.onFailure(new AuthenticationFailureBadCredentialsEvent(
                attempt(), new BadCredentialsException("Bad credentials")));

        List<AuditLog> rows = saved();
        assertThat(rows.get(0).getDetail()).contains("mail server");
        assertThat(rows.get(1).getDetail()).doesNotContain("mail server");
    }

    @Test
    @DisplayName("a mailbox sign-in is recorded, under its own action and without an app_user write")
    void mailboxSuccessIsAudited() {
        // The weakest credential in the building used to produce no record of a
        // successful sign in at all, while every miss was logged. An attacker
        // guessing mailbox passwords therefore left a trail of LOGIN_FAILED rows and
        // nothing whatever for the hit.
        listener.onSuccess(new AuthenticationSuccessEvent(
                UsernamePasswordAuthenticationToken.authenticated(
                        new MailboxUserDetails(ADDRESS), null, List.of())));

        verify(users, never()).save(any(User.class));
        assertThat(actions()).containsExactly("MAILBOX_LOGIN_SUCCESS");
        assertThat(saved().get(0).getTarget()).isEqualTo(ADDRESS);
    }

    @Test
    @DisplayName("a console sign-in clears the counter and is recorded under its own action")
    void consoleSuccessClearsTheCounter() {
        User user = consoleUser();
        user.setFailedLoginAttempts(4);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(1));

        listener.onSuccess(new AuthenticationSuccessEvent(
                UsernamePasswordAuthenticationToken.authenticated(
                        new AppUserDetails(user), null, List.of())));

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        verify(users).save(user);
        assertThat(actions()).containsExactly("LOGIN_SUCCESS");
    }

    private AuthenticationFailureBadCredentialsEvent mailboxRefusedIt() {
        return new AuthenticationFailureBadCredentialsEvent(
                attempt(), new MailboxBadCredentialsException("Bad credentials"));
    }

    private static Authentication attempt() {
        return UsernamePasswordAuthenticationToken.unauthenticated(ADDRESS, "guess");
    }

    private static User consoleUser() {
        return new User(ADDRESS, "$2a$04$notarealhash", "Priya", Role.OWNER, "test");
    }

    private List<AuditLog> saved() {
        List<AuditLog> rows = new ArrayList<>();
        org.mockito.ArgumentCaptor<AuditLog> captor =
                org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        rows.addAll(captor.getAllValues());
        return rows;
    }

    private List<String> actions() {
        return saved().stream().map(AuditLog::getAction).toList();
    }
}
