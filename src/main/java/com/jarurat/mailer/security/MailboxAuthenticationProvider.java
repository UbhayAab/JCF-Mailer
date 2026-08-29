package com.jarurat.mailer.security;

import com.jarurat.mailer.webmail.MailboxAccess;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * The second half of one login form: when app_user does not recognise the
 * credentials, the address is offered to the mail server instead.
 *
 * Most people at Jarurat have a mailbox and no console account. Before this, the
 * login page could only ever tell them their password was wrong, and mail on a
 * phone was two passwords deep. Now the same form works for both, and the console
 * password stays the thing that opens the console.
 *
 * THE SECURITY BOUNDARY, and it does not move: a session minted here holds
 * Role.MAILBOX, which is MAIL_READ and MAIL_SEND and nothing else, no matter what
 * an app_user row for the same address may say. The principal is built from
 * Role.MAILBOX directly and this class never reads app_user at all, so there is no
 * path by which an OWNER row could be picked up. A mailbox password buys its own
 * mailbox, the same thing it already buys in any IMAP client, and it never buys the
 * ability to send a campaign.
 *
 * Ordering is the other half of the contract. SecurityConfig registers this after
 * DaoAuthenticationProvider in one ProviderManager, and ProviderManager stops at
 * the first provider that returns a result, so a real console credential always
 * wins and this class is not even called when it does. When the DAO provider throws
 * instead, ProviderManager's own catch decides what happens next, and the split
 * matters: an AccountStatusException is rethrown immediately and never reaches this
 * class, while a BadCredentialsException falls through to here.
 *
 * A disabled or expired console account therefore still stops the whole login dead,
 * which is right, because deactivating somebody is meant to end their access. A
 * locked one deliberately does not: SecurityConfig turns that case into
 * ConsoleLockedException so it falls through to this class, because the lockout is a
 * console control and welding it to mail availability meant five wrong guesses could
 * take a person's own mailbox away from them. Nothing is loosened by that, since the
 * check still runs before the password is compared and the most this class can ever
 * hand back is Role.MAILBOX.
 *
 * That is also why the only failure this class raises is a BadCredentialsException.
 * ProviderManager publishes whichever exception it ends up throwing, and
 * LoginAttemptListener counts failed attempts off exactly that event. An exception
 * from a different family here, thrown after the DAO provider had already produced
 * the right one, would replace it and the lockout counter would silently stop
 * advancing on a console account being guessed at.
 *
 * MailboxBadCredentialsException is that same family with a label on it, so a
 * listener can tell which of the two stores turned the password down without any of
 * the above changing. Registering it with the event publisher is not optional and
 * SecurityConfig does it: the publisher matches exceptions by exact class name, so
 * an unregistered subclass would publish nothing and take the lockout counter, the
 * last-login stamp and the login audit trail with it.
 *
 * Deliberately not a bean. Spring Security's InitializeAuthenticationProviderBeanManagerConfigurer
 * picks up any AuthenticationProvider bean and makes it the whole of the global
 * AuthenticationManager, which would leave that manager holding this provider and no
 * DAO provider at all. Nothing on the shipped chains uses it, because the console
 * chain is given its own manager, but a manager that accepts only mailbox passwords
 * sitting one misconfiguration away from being reached is not worth the convenience
 * of an annotation. SecurityConfig constructs this instead.
 */
public class MailboxAuthenticationProvider implements AuthenticationProvider {

    private final MailboxAccess mailboxes;

    public MailboxAuthenticationProvider(MailboxAccess mailboxes) {
        this.mailboxes = mailboxes;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        // Belt and braces against a future reordering. ProviderManager already
        // returns before it reaches a provider that has been beaten to it, so on
        // the shipped configuration this is never true.
        if (authentication.isAuthenticated()) {
            return null;
        }

        // LoginAddress rather than a trim and a lower case written out here. Both
        // spellings are the same two operations today, and that is exactly the
        // problem: LoginRateLimiter keys on this string, and when the two files each
        // carried their own copy of the rule they drifted, and a control character in
        // front of the address bought a counter of its own while still signing in
        // here. One method, called by both, is what makes that impossible rather than
        // unlikely.
        String address = LoginAddress.canonical(authentication.getName());
        String secret = authentication.getCredentials() == null
                ? null : authentication.getCredentials().toString();
        if (address.isEmpty() || secret == null || secret.isEmpty()) {
            throw new MailboxBadCredentialsException("Bad credentials");
        }

        if (!mailboxes.accepts(address, secret)) {
            throw new MailboxBadCredentialsException("Bad credentials");
        }

        MailboxUserDetails principal = new MailboxUserDetails(address);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
