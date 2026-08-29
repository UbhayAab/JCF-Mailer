package com.jarurat.mailer.security;

import org.springframework.security.core.userdetails.UserDetails;

/**
 * What every screen needs from whoever is signed in, whichever of the two
 * identity systems vouched for them.
 *
 * There are two now. AppUserDetails is a row in app_user, authenticated by
 * DaoAuthenticationProvider against a bcrypt hash we hold. MailboxUserDetails is
 * an address that proved its password to Stalwart and has no row here at all.
 * The page controllers ask the same three questions of both, so they ask them
 * through this interface rather than through either concrete class. Anything that
 * needs the app_user row itself still has to test for AppUserDetails, and
 * LoginAttemptListener does exactly that on purpose.
 */
public interface SignedInUser extends UserDetails {

    /** Drives the permission list handed to the templates. */
    Role getRole();

    /** Falls back to the address when there is no name to show. */
    String getFullName();
}
