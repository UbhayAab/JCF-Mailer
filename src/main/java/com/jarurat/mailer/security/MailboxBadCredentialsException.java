package com.jarurat.mailer.security;

import org.springframework.security.authentication.BadCredentialsException;

/**
 * A sign-in that the mail server refused, as opposed to one app_user refused.
 *
 * It exists so LoginAttemptListener can say which store produced the failure it is
 * looking at. ProviderManager publishes whichever exception it ends up throwing, and
 * with two providers on one form that exception is normally the second one's, so
 * without a marker every failure looks identical from the listener and an audit row
 * cannot say whether the console or the mailbox turned the password down.
 *
 * It stays a BadCredentialsException subclass on purpose: the failure URL, the
 * generic message on the login page and every caller that catches BadCredentials all
 * keep working unchanged. Being a subclass is not by itself enough to keep the event
 * flowing, though. DefaultAuthenticationEventPublisher.getEventConstructor looks the
 * exception up by exact class name and falls back to a default constructor that is
 * null unless somebody sets one, so an unregistered subclass publishes no event at
 * all - which would silently take out the lockout counter, the last-login stamp and
 * the whole login audit trail. SecurityConfig registers the mapping explicitly for
 * that reason, and MailboxAuthenticationProviderTest holds it in place.
 */
public class MailboxBadCredentialsException extends BadCredentialsException {

    private static final long serialVersionUID = 1L;

    public MailboxBadCredentialsException(String message) {
        super(message);
    }
}
