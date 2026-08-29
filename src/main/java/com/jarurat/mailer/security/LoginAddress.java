package com.jarurat.mailer.security;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one place the address typed into the login form is turned into a string, so
 * the rate limiter and the two authentication providers cannot key on different
 * things.
 *
 * This class exists because they did, and it was a complete bypass of the limiter.
 * The limiter used to collapse the two blank classes below, while everything that
 * actually authenticates used String.trim(), which strips every character at or
 * below U+0020. The twenty seven C0 control characters are in neither blank class,
 * so they survived into the limiter key and were stripped everywhere else: an
 * address with U+0001 in front of it got a counter of its own and signed in as the
 * address without it. Measured on the running application, three hundred consecutive
 * guesses at one mailbox and not one refusal.
 *
 * The rule that stops that happening again is the shape of this file rather than a
 * comment on either caller. canonical() is exactly what authentication uses, and
 * key() is a function OF canonical() rather than a second reading of the raw
 * parameter. Any two submissions that authenticate as the same mailbox therefore
 * produce the same canonical string by definition, and applying the same collapse to
 * both cannot pull them apart again. Folding can only ever be too generous, which
 * costs an attacker a counter they share with somebody else, never too mean, which
 * is the direction that hands out free guesses.
 *
 * The collapse on top is still worth keeping. trim() leaves U+00A0 and the other
 * Unicode separators in place, and MailboxAccess.normalise only tests for an ASCII
 * space, so a padded copy of a real address is refused by the mail server but does
 * mint a distinct canonical string. Collapsing those folds the junk onto the address
 * it was aimed at.
 */
public final class LoginAddress {

    /** ASCII blanks and Unicode separators, the ones trim() does not already remove. */
    private static final Pattern BLANKS = Pattern.compile("[\\s\\p{Z}]+");

    private LoginAddress() {
    }

    /**
     * The address exactly as the credential stores will see it.
     *
     * Spring's UsernamePasswordAuthenticationFilter trims the submitted username
     * before it builds the token, MailboxAuthenticationProvider and
     * AppUserDetailsService both call this, and Locale.ROOT rather than the default
     * locale keeps a server running under a Turkish locale from folding "I" to a
     * dotless i and looking up an address nobody typed.
     */
    public static String canonical(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The counter key for one address, which is canonical() with the blanks that
     * survive it removed as well.
     */
    public static String key(String raw) {
        return BLANKS.matcher(canonical(raw)).replaceAll("");
    }
}
