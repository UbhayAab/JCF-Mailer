package com.jarurat.mailer.security;

import com.jarurat.mailer.models.AuditLog;
import com.jarurat.mailer.models.User;
import com.jarurat.mailer.repositories.AuditLogRepository;
import com.jarurat.mailer.repositories.UserRepository;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Locks a console account for 15 minutes after 5 bad passwords, to blunt credential
 * stuffing, and writes the row that says a sign-in happened.
 *
 * What this lock does and does not reach is worth being exact about, because the one
 * login form now speaks to two credential stores. It counts against app_user and it
 * closes app_user. It does not close the mailbox: SecurityConfig raises
 * ConsoleLockedException rather than LockedException so the mailbox provider still
 * runs for a locked address, which is what stops a run of wrong guesses at a console
 * account from taking that person's mail away as well. Guessing at mailbox passwords
 * is bounded by LoginRateLimiter instead, which needs no app_user row and therefore
 * covers the people this counter never sees.
 */
@Component
public class LoginAttemptListener {

    private static final int MAX_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 15;

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    public LoginAttemptListener(UserRepository userRepository, AuditLogRepository auditLogRepository) {
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        String email = normalise(event.getAuthentication().getName());
        boolean mailServerRefusedIt = event.getException() instanceof MailboxBadCredentialsException;

        userRepository.findByEmail(email).ifPresent(user -> {
            // An account that is already locked is not counted again. It has to be
            // skipped explicitly now, because a locked account no longer short
            // circuits the manager: the request carries on to the mailbox provider,
            // fails there too, and arrives back here as an ordinary bad-credentials
            // event. Counting those would let an attacker extend somebody's lock
            // indefinitely by continuing to knock.
            if (user.isLocked()) return;

            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= MAX_ATTEMPTS) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
                user.setFailedLoginAttempts(0);
                auditLogRepository.save(new AuditLog(email, "ACCOUNT_LOCKED", email,
                        "Locked for " + LOCK_MINUTES + " minutes after " + MAX_ATTEMPTS + " failed attempts", null));
            }
            userRepository.save(user);
        });

        // Which store refused it is the useful half of this row during an incident.
        // "Refused by both" against an address with no console account is somebody
        // working through the staff list; "refused by the console" alone means the
        // mail server was never even reached.
        auditLogRepository.save(new AuditLog(email, "LOGIN_FAILED", email,
                mailServerRefusedIt ? "Refused by app_user and by the mail server."
                        : "Refused by app_user.", null));
    }

    /**
     * The audit row is written for both kinds of sign-in and the repository work only
     * for a console one, and the order of those two statements is the point.
     *
     * A mailbox principal has no app_user row behind it, so anything saved from one
     * would insert a row for an address that deliberately has no console account, and
     * would reset the failed-attempt counter on a console account that is in the
     * middle of being guessed at. Neither happens, because the type test guards the
     * repository. But the audit write used to sit inside that same guard, which meant
     * the weakest credential in the building produced no record of a successful sign
     * in at all while its failures were logged every time - so a run of guesses left
     * a LOGIN_FAILED row for every miss and nothing whatever for the hit. That is
     * backwards for anyone reading the audit screen after the fact, and it is why the
     * write is above the test now.
     */
    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        Object principal = event.getAuthentication().getPrincipal();
        boolean console = principal instanceof AppUserDetails;
        String name = normalise(event.getAuthentication().getName());

        auditLogRepository.save(new AuditLog(name,
                console ? "LOGIN_SUCCESS" : "MAILBOX_LOGIN_SUCCESS", name, null, null));

        if (!console) return;

        User user = ((AppUserDetails) principal).getUser();
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
    }

    /**
     * Locale.ROOT rather than the default, so a server running under a Turkish locale
     * cannot fold "I" to a dotless i and look up an address the providers never used.
     */
    private static String normalise(String name) {
        return String.valueOf(name).toLowerCase(Locale.ROOT);
    }
}
