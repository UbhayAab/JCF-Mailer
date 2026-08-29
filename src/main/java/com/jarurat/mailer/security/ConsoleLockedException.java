package com.jarurat.mailer.security;

import org.springframework.security.authentication.BadCredentialsException;

/**
 * The console account for this address is locked. Says nothing about the mailbox.
 *
 * This is deliberately not a LockedException, and the difference is the whole point.
 * ProviderManager rethrows any AccountStatusException the moment a provider raises
 * it and never consults the providers after it, so the plain LockedException that
 * AppUserDetails.isAccountNonLocked used to raise took the mailbox down with the
 * console: five wrong guesses at owner@ left the person who owns that mailbox unable
 * to read their own mail for fifteen minutes, and let anyone who knew a staff
 * address hold them out of webmail for five requests per fifteen minutes, forever.
 * The lockout is a console control and it now stops at the console.
 *
 * Nothing is loosened on the console side. The check still runs before the password
 * is compared, so a locked account cannot be authenticated by DaoAuthenticationProvider
 * whatever password arrives with it. What changes is only that the request carries on
 * to MailboxAuthenticationProvider, which can mint nothing but Role.MAILBOX, so the
 * most a locked console account can now obtain is the mailbox its own mail password
 * already opens in any IMAP client.
 *
 * Being a BadCredentialsException also keeps the single generic failure message on
 * the login page, so this does not become a way to ask the server whether an address
 * has a console account.
 */
public class ConsoleLockedException extends BadCredentialsException {

    private static final long serialVersionUID = 1L;

    public ConsoleLockedException(String message) {
        super(message);
    }
}
