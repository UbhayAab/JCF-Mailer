package com.jarurat.mailer.security;

import com.jarurat.mailer.models.User;
import com.jarurat.mailer.webmail.MailboxAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The order of the two providers is the security boundary of the whole one-login
 * change, so it is tested on the manager SecurityConfig actually builds rather than
 * on either provider alone. Three things have to stay true together and none of them
 * is visible from reading one class: a console password always wins, a mailbox
 * password never walks past a lockout, and a login that fails both ways still comes
 * out as the one exception LoginAttemptListener counts.
 */
class MailboxAuthenticationProviderTest {

    private static final String ADDRESS = "priya@jarurat.care";
    private static final String CONSOLE_PASSWORD = "console-password";
    private static final String MAILBOX_PASSWORD = "mailbox-password";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AppUserDetailsService users = mock(AppUserDetailsService.class);
    private final MailboxAccess mailboxes = mock(MailboxAccess.class);
    private final List<Object> published = new ArrayList<>();

    private AuthenticationManager manager;

    @BeforeEach
    void buildTheRealManager() {
        ApplicationEventPublisher capture = published::add;
        AuthenticationEventPublisher publisher = new DefaultAuthenticationEventPublisher(capture);
        manager = new SecurityConfig().authenticationManager(
                users, encoder, mailboxes, provider(publisher), capture);
    }

    @Test
    @DisplayName("a real console credential wins and the mail server is never asked")
    void consoleCredentialWins() {
        when(users.loadUserByUsername(ADDRESS)).thenReturn(consoleUser(Role.OWNER, false));

        Authentication result = manager.authenticate(token(CONSOLE_PASSWORD));

        assertThat(result.getPrincipal()).isInstanceOf(AppUserDetails.class);
        assertThat(names(result)).contains("CAMPAIGNS_SEND");
        verify(mailboxes, never()).accepts(anyString(), anyString());
    }

    @Test
    @DisplayName("a mailbox password grants MAIL_READ and MAIL_SEND and nothing the app_user row carries")
    void mailboxPasswordNeverInheritsTheConsoleRole() {
        // The same address owns Campaign Studio. The mailbox password must still buy
        // only the mailbox, which is the point of the whole arrangement.
        when(users.loadUserByUsername(ADDRESS)).thenReturn(consoleUser(Role.OWNER, false));
        when(mailboxes.accepts(ADDRESS, MAILBOX_PASSWORD)).thenReturn(true);

        Authentication result = manager.authenticate(token(MAILBOX_PASSWORD));

        assertThat(result.getPrincipal()).isInstanceOf(MailboxUserDetails.class);
        assertThat(names(result)).containsExactlyInAnyOrder("MAIL_READ", "MAIL_SEND");
        assertThat(((MailboxUserDetails) result.getPrincipal()).getRole()).isEqualTo(Role.MAILBOX);
    }

    @Test
    @DisplayName("an address with no app_user row at all can still sign in to its mailbox")
    void mailboxOnlyPersonCanSignIn() {
        when(users.loadUserByUsername(ADDRESS)).thenThrow(new UsernameNotFoundException("no row"));
        when(mailboxes.accepts(ADDRESS, MAILBOX_PASSWORD)).thenReturn(true);

        Authentication result = manager.authenticate(token(MAILBOX_PASSWORD));

        assertThat(result.getPrincipal()).isInstanceOf(MailboxUserDetails.class);
        assertThat(result.getName()).isEqualTo(ADDRESS);
    }

    @Test
    @DisplayName("a locked console account cannot be signed into the console by any password")
    void lockoutStillClosesTheConsole() {
        // The check runs before the password is compared, so the correct console
        // password is worth nothing here. That is the whole of what the lock is for.
        when(users.loadUserByUsername(ADDRESS)).thenReturn(consoleUser(Role.OWNER, true));
        when(mailboxes.accepts(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> manager.authenticate(token(CONSOLE_PASSWORD)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("a locked console account still gets its mailbox, and only its mailbox")
    void lockoutDoesNotReachTheMailbox() {
        // The lockout is a console control. Welding it to mail meant five wrong
        // guesses at a named staff address took that person's own mailbox away for
        // fifteen minutes, and let anybody who knew the address keep it away. The
        // console stays shut: what comes back is Role.MAILBOX, whatever the app_user
        // row says, and this session cannot send a campaign with it.
        when(users.loadUserByUsername(ADDRESS)).thenReturn(consoleUser(Role.OWNER, true));
        when(mailboxes.accepts(ADDRESS, MAILBOX_PASSWORD)).thenReturn(true);

        Authentication result = manager.authenticate(token(MAILBOX_PASSWORD));

        assertThat(result.getPrincipal()).isInstanceOf(MailboxUserDetails.class);
        assertThat(names(result)).containsExactlyInAnyOrder("MAIL_READ", "MAIL_SEND");
    }

    @Test
    @DisplayName("a disabled console account still stops the login dead")
    void aDisabledAccountIsNotABackDoor() {
        // Unlike a lock, deactivating somebody is meant to end their access rather
        // than move it, so this one keeps the AccountStatusException short circuit and
        // the mail server is never asked.
        when(users.loadUserByUsername(ADDRESS)).thenReturn(consoleUser(Role.OWNER, false, false));
        when(mailboxes.accepts(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> manager.authenticate(token(MAILBOX_PASSWORD)))
                .isInstanceOf(DisabledException.class);
        verify(mailboxes, never()).accepts(anyString(), anyString());
    }

    @Test
    @DisplayName("an account that is deactivated and also locked still stops the login dead")
    void aDeactivatedAccountIsNotReachableThroughItsOwnLock() {
        // The hole the lockout fix opened, and the one aDisabledAccountIsNotABackDoor
        // above cannot see, because it uses an account that is disabled and not locked
        // and passes either way. consolePreChecks used to test the lock first, so this
        // pair raised ConsoleLockedException, the lock case, and fell through to the
        // mailbox provider: a deactivated person kept their mail for the length of the
        // lock window. It needs nothing unusual to reach, because AdminApi clears
        // lockedUntil only when activating, so an account guessed at until it locks and
        // then deactivated is left in exactly this state.
        when(users.loadUserByUsername(ADDRESS)).thenReturn(consoleUser(Role.OWNER, true, false));
        when(mailboxes.accepts(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> manager.authenticate(token(MAILBOX_PASSWORD)))
                .isInstanceOf(DisabledException.class);
        verify(mailboxes, never()).accepts(anyString(), anyString());
    }

    @Test
    @DisplayName("a mailbox failure is still published as the event the lockout counter listens for")
    void theMarkedFailureStillPublishesItsEvent() {
        // MailboxAuthenticationProvider throws a BadCredentialsException subclass so
        // a listener can tell the two stores apart. DefaultAuthenticationEventPublisher
        // matches exceptions by exact class name and has no default for the ones it
        // does not know, so an unregistered subclass publishes nothing at all and the
        // lockout, the last-login stamp and every LOGIN_FAILED row go quiet together.
        // SecurityConfig registers the mapping; this is what notices if it stops.
        when(users.loadUserByUsername(ADDRESS)).thenThrow(new UsernameNotFoundException("no row"));
        when(mailboxes.accepts(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> manager.authenticate(token("wrong-everywhere")))
                .isInstanceOf(MailboxBadCredentialsException.class);
        assertThat(published).hasAtLeastOneElementOfType(AuthenticationFailureBadCredentialsEvent.class);
    }

    @Test
    @DisplayName("failing both ways is a bad credentials failure, so the lockout counter still advances")
    void bothFailuresStillFeedTheLockoutCounter() {
        // LoginAttemptListener counts AuthenticationFailureBadCredentialsEvent. If the
        // mailbox provider threw any other type it would replace the DAO provider's,
        // that event would stop being published, and the account lockout would quietly
        // stop working against exactly the password guessing it exists to blunt.
        when(users.loadUserByUsername(ADDRESS)).thenReturn(consoleUser(Role.ADMIN, false));
        when(mailboxes.accepts(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> manager.authenticate(token("wrong-everywhere")))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(published).hasAtLeastOneElementOfType(AuthenticationFailureBadCredentialsEvent.class);
    }

    @Test
    @DisplayName("an empty password never reaches the mail server")
    void emptyPasswordIsRejectedLocally() {
        when(users.loadUserByUsername(ADDRESS)).thenThrow(new UsernameNotFoundException("no row"));

        assertThatThrownBy(() -> manager.authenticate(token("")))
                .isInstanceOf(BadCredentialsException.class);
        verify(mailboxes, never()).accepts(anyString(), anyString());
    }

    @Test
    @DisplayName("the address is lower cased before it is offered to the mail server")
    void addressIsNormalised() {
        when(users.loadUserByUsername(anyString())).thenThrow(new UsernameNotFoundException("no row"));
        when(mailboxes.accepts(ADDRESS, MAILBOX_PASSWORD)).thenReturn(true);

        Authentication result = manager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("  Priya@Jarurat.Care ", MAILBOX_PASSWORD));

        assertThat(result.getName()).isEqualTo(ADDRESS);
    }

    private static Authentication token(String password) {
        return UsernamePasswordAuthenticationToken.unauthenticated(ADDRESS, password);
    }

    private AppUserDetails consoleUser(Role role, boolean locked) {
        return consoleUser(role, locked, true);
    }

    private AppUserDetails consoleUser(Role role, boolean locked, boolean active) {
        User user = new User(ADDRESS, encoder.encode(CONSOLE_PASSWORD), "Priya", role, "test");
        if (locked) user.setLockedUntil(LocalDateTime.now().plusMinutes(15));
        user.setActive(active);
        return new AppUserDetails(user);
    }

    private static List<String> names(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    /** Hands SecurityConfig the publisher it would otherwise take from the context. */
    private static ObjectProvider<AuthenticationEventPublisher> provider(AuthenticationEventPublisher publisher) {
        return new ObjectProvider<>() {
            @Override public AuthenticationEventPublisher getObject() throws BeansException { return publisher; }
            @Override public AuthenticationEventPublisher getObject(Object... args) { return publisher; }
            @Override public AuthenticationEventPublisher getIfAvailable() { return publisher; }
            @Override public AuthenticationEventPublisher getIfUnique() { return publisher; }
        };
    }
}
