package com.jarurat.mailer.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counters that stand between the login form and unlimited guessing at mailbox
 * passwords.
 *
 * Worth testing in its own right, and not only through the filter, because every
 * property here is invisible until somebody is either locked out of their mail or
 * quietly not being counted at all. Both failures are silent in production, and the
 * previous version of this file shipped with both: it only ever fed the normaliser
 * spaces, which the two spellings of the rule agreed on, and it never touched
 * clientKey at all.
 */
class LoginRateLimiterTest {

    private static final String ADDRESS = "priya@jarurat.care";
    private static final String CLIENT = "203.0.113.9";

    /**
     * U+0001, written as a cast rather than as an escape so that it is obvious this
     * is a control character and not a typo. It is one of the twenty seven C0
     * characters that String.trim removes and the blank classes do not.
     */
    private static final String CONTROL = String.valueOf((char) 0x01);

    /**
     * U+00A0, the non-breaking space, written the same way and for the same reason.
     * It is the other direction: trim keeps it, so it never authenticates, and the
     * collapse is what folds it onto the address it was aimed at.
     */
    private static final String NBSP = String.valueOf((char) 0x00A0);

    private final LoginRateLimiter limiter = new LoginRateLimiter();

    @BeforeEach
    void freshProcess() {
        limiter.reset();
    }

    @Test
    @DisplayName("an address over its budget is delayed and never refused")
    void theAddressBudgetBuysADelayAndNotARefusal() {
        // The whole of finding 2d. A hard refusal keyed on an address is a way to
        // hold the owner of that address out of their own mail, because the correct
        // password is refused along with every wrong one. Nothing here may ever
        // answer refused() for an address, however long somebody knocks.
        for (int i = 0; i < LoginRateLimiter.PER_ADDRESS; i++) {
            LoginRateLimiter.Decision inside = limiter.reserve(ADDRESS, CLIENT);
            assertThat(inside.refused()).as("attempt %d", i + 1).isFalse();
            assertThat(inside.delayMillis()).as("attempt %d", i + 1).isZero();
        }

        LoginRateLimiter.Decision first = limiter.reserve(ADDRESS, CLIENT);
        assertThat(first.refused()).isFalse();
        assertThat(first.delayMillis()).isEqualTo(LoginRateLimiter.FIRST_DELAY_MILLIS);

        LoginRateLimiter.Decision second = limiter.reserve(ADDRESS, CLIENT);
        assertThat(second.delayMillis()).isGreaterThan(first.delayMillis());

        for (int i = 0; i < 50; i++) {
            LoginRateLimiter.Decision later = limiter.reserve(ADDRESS, "10.0.0." + i);
            assertThat(later.refused()).as("attempt %d past the budget", i).isFalse();
            assertThat(later.delayMillis()).isLessThanOrEqualTo(LoginRateLimiter.MAX_DELAY_MILLIS);
        }
    }

    @Test
    @DisplayName("a character trim removes and the blank classes do not shares one counter")
    void aControlCharacterCannotBuyAFreshCounter() {
        // Finding 2a, and the reason LoginAddress exists. The limiter used to collapse
        // only the blank classes while everything that authenticates called trim, so
        // an address with a C0 control character in front of it was a different key
        // here and the same login everywhere else. Measured on the running
        // application: three hundred consecutive guesses, no refusals.
        assertThat(LoginAddress.key(CONTROL + ADDRESS)).isEqualTo(LoginAddress.key(ADDRESS));

        for (int i = 0; i < LoginRateLimiter.PER_ADDRESS; i++) {
            limiter.reserve(ADDRESS, CLIENT);
        }

        assertThat(limiter.reserve(CONTROL + ADDRESS, CLIENT).delayMillis()).isPositive();
        assertThat(limiter.reserve(ADDRESS + CONTROL, CLIENT).delayMillis()).isPositive();
        // A tab and a null are the same class of character and were the same bypass.
        assertThat(limiter.reserve("\t" + ADDRESS, CLIENT).delayMillis()).isPositive();
        assertThat(limiter.reserve((char) 0x00 + ADDRESS, CLIENT).delayMillis()).isPositive();
    }

    @Test
    @DisplayName("case, padding and Unicode separators share one counter as well")
    void variantsCannotBuyAFreshCounter() {
        // The safe direction, which the old normalisation did get right and which is
        // kept: none of these authenticate as anything, so folding them onto the real
        // address can only ever cost the attacker a counter they now share.
        String[] variants = {
                "priya@jarurat.care", "PRIYA@JARURAT.CARE", "  Priya@Jarurat.Care  ",
                "priya @jarurat.care", "priya@jarurat.care ", NBSP + "priya@jarurat.care"
        };
        for (int i = 0; i < LoginRateLimiter.PER_ADDRESS; i++) {
            limiter.reserve(variants[i % variants.length], "10.0.0." + i);
        }

        assertThat(limiter.reserve(ADDRESS, "198.51.100.1").delayMillis()).isPositive();
    }

    @Test
    @DisplayName("a successful sign-in clears that address immediately")
    void successForgivesTheAddress() {
        for (int i = 0; i <= LoginRateLimiter.PER_ADDRESS; i++) limiter.reserve(ADDRESS, CLIENT);
        assertThat(limiter.reserve(ADDRESS, CLIENT).delayMillis()).isPositive();

        limiter.succeeded("  PRIYA@jarurat.care ");

        assertThat(limiter.reserve(ADDRESS, CLIENT).delayMillis()).isZero();
    }

    @Test
    @DisplayName("a client that has spent its budget is refused outright")
    void theClientBudgetIsAHardRefusal() {
        // The one place a refusal is legitimate, because the budget being spent
        // belongs to the caller spending it and turning them away costs nobody else.
        for (int i = 0; i < LoginRateLimiter.PER_CLIENT; i++) {
            String address = "person" + i + "@jarurat.care";
            assertThat(limiter.reserve(address, CLIENT).refused()).as("attempt %d", i + 1).isFalse();
        }

        LoginRateLimiter.Decision refused = limiter.reserve("fresh@jarurat.care", CLIENT);
        assertThat(refused.refused()).isTrue();
        assertThat(refused.retryAfterSeconds()).isLessThanOrEqualTo(LoginRateLimiter.WINDOW_MINUTES * 60L);
        // A different client is unaffected, so this is a limit and not an outage.
        assertThat(limiter.reserve("fresh@jarurat.care", "198.51.100.7").refused()).isFalse();
    }

    @Test
    @DisplayName("a refused client cannot also run up somebody else's address")
    void aRefusedAttemptDoesNotTouchTheAddress() {
        for (int i = 0; i <= LoginRateLimiter.PER_CLIENT; i++) {
            limiter.reserve("noise" + i + "@jarurat.care", CLIENT);
        }
        for (int i = 0; i < 200; i++) {
            assertThat(limiter.reserve(ADDRESS, CLIENT).refused()).isTrue();
        }

        // Priya arrives from her own connection and owes nothing for any of that.
        assertThat(limiter.reserve(ADDRESS, "198.51.100.4").delayMillis()).isZero();
    }

    @Test
    @DisplayName("a successful sign-in does not clear the client's own counter")
    void successDoesNotRinseTheClientCounter() {
        // Otherwise anybody holding one working password could sign in between
        // guesses at everybody else and never reach the client limit at all.
        for (int i = 0; i < LoginRateLimiter.PER_CLIENT; i++) {
            limiter.reserve("victim" + i + "@jarurat.care", CLIENT);
        }
        limiter.succeeded("victim0@jarurat.care");

        assertThat(limiter.reserve("someone-else@jarurat.care", CLIENT).refused()).isTrue();
    }

    @Test
    @DisplayName("the client key is the peer nginx saw, and the socket address without one")
    void theClientKeyIsThePeerAndNotWhateverTheCallerSent() {
        // Finding 2e, which had no test at all: nothing in the tree set a forwarded
        // header or a remote address anywhere. Reading the caller's own element gave
        // an attacker a fresh key per request and gave them the office's key to
        // poison. Both were measured against the running application.
        //
        // Reading the LAST forwarded element, which was the first fix, is correct
        // reasoning about a header that never arrives: production sets
        // server.forward-headers-strategy=framework, so ForwardedHeaderFilter strips
        // every X-Forwarded-* header ahead of the security chain and rewrites
        // getRemoteAddr() from the caller's first element. X-Real-IP is what nginx
        // overwrites and what actually survives. See ClientIp.
        assertThat(LoginRateLimiter.clientKey(forwarded("198.51.100.7, 203.0.113.9")))
                .isEqualTo("203.0.113.9");
        assertThat(LoginRateLimiter.clientKey(forwarded("a, b, 203.0.113.9")))
                .isEqualTo("203.0.113.9");
        assertThat(LoginRateLimiter.clientKey(forwarded("203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(LoginRateLimiter.clientKey(forwarded("   203.0.113.9   "))).isEqualTo("203.0.113.9");

        MockHttpServletRequest direct = new MockHttpServletRequest("POST", "/login");
        direct.setRemoteAddr("192.0.2.44");
        assertThat(LoginRateLimiter.clientKey(direct)).isEqualTo("192.0.2.44");

        MockHttpServletRequest blank = new MockHttpServletRequest("POST", "/login");
        blank.setRemoteAddr("192.0.2.44");
        blank.addHeader("X-Forwarded-For", "   ");
        assertThat(LoginRateLimiter.clientKey(blank)).isEqualTo("192.0.2.44");

        assertThat(LoginRateLimiter.clientKey(null)).isEmpty();
    }

    @Test
    @DisplayName("rotating the forwarded header does not buy a fresh client budget")
    void theForwardedHeaderCannotBeRotatedForMoreAttempts() {
        for (int i = 0; i < LoginRateLimiter.PER_CLIENT; i++) {
            String client = LoginRateLimiter.clientKey(forwarded("10.1.1." + i + ", 203.0.113.9"));
            assertThat(limiter.reserve("person" + i + "@jarurat.care", client).refused())
                    .as("attempt %d", i + 1).isFalse();
        }

        String next = LoginRateLimiter.clientKey(forwarded("10.9.9.9, 203.0.113.9"));
        assertThat(limiter.reserve("another@jarurat.care", next).refused()).isTrue();
    }

    @Test
    @DisplayName("the tracked ceiling is the actual ceiling")
    void theCeilingIsEnforcedByEviction() {
        // Finding 2f. The old sweep only removed entries older than the window, so
        // inside one window it freed nothing and the map grew without limit while
        // scanning itself on the request thread. Measured on the running application:
        // seventy thousand entries against a stated twenty thousand.
        for (int i = 0; i < LoginRateLimiter.MAX_TRACKED * 3; i++) {
            // An empty client key is not tracked, so this charges the address side
            // only and nothing is refused on the way through.
            limiter.reserve("person" + i + "@jarurat.care", "");
        }

        assertThat(limiter.trackedAddresses()).isEqualTo(LoginRateLimiter.MAX_TRACKED);
    }

    @Test
    @DisplayName("a submission with no address at all still costs the client")
    void blankAddressStillChargesTheClient() {
        for (int i = 0; i < LoginRateLimiter.PER_CLIENT; i++) limiter.reserve("", CLIENT);

        assertThat(limiter.reserve("", CLIENT).refused()).isTrue();
        // The empty string is not itself a tracked address, so nobody real is caught
        // by it and the table has not grown by an entry nobody can be held to.
        assertThat(limiter.trackedAddresses()).isZero();
        assertThat(limiter.reserve(ADDRESS, "198.51.100.8").delayMillis()).isZero();
    }

    /**
     * Both headers, because nginx sends both and they disagree by design.
     * X-Forwarded-For is built with $proxy_add_x_forwarded_for, so it is the caller's
     * claim with the real peer appended; X-Real-IP is set from $remote_addr, which
     * overwrites whatever the caller sent. Only the second is trustworthy, and only
     * the second survives ForwardedHeaderFilter, which strips every X-Forwarded-*
     * header before the application sees it. Sending only the first, as these tests
     * used to, tested a header the running application can never read.
     */
    private static MockHttpServletRequest forwarded(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", header);
        int comma = header.lastIndexOf(',');
        String peer = (comma < 0 ? header : header.substring(comma + 1)).trim();
        if (!peer.isEmpty()) request.addHeader(ClientIp.TRUSTED_HEADER, peer);
        return request;
    }
}
