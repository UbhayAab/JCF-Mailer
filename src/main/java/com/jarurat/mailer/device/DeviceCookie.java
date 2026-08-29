package com.jarurat.mailer.device;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * The cookie itself: what is in it, how it is written, and how it is read back.
 *
 * The value is two parts, "v1.selector.secret". The selector is a public handle that
 * names one row and the secret is the credential, and splitting them is what keeps
 * the lookup a single indexed equality test without a hash of the credential ever
 * being the thing we search on. It also means the secret never appears in a query,
 * a query log or a slow query report.
 *
 * SameSite=Lax rather than Strict, and that is a decision rather than a default.
 * Strict withholds the cookie on the first request of a cross-site navigation, so
 * following a link to the mailbox out of any other application would land on the
 * login page and only work on the second attempt, which is precisely the complaint
 * this whole feature exists to remove. Lax still withholds it from every cross-site
 * POST and from every subresource load, which is the part that matters: a hostile
 * page cannot make an authenticated request with it.
 *
 * ResponseCookie rather than jakarta Cookie for the write, because SameSite is not
 * expressible on the servlet API's cookie in this container and a cookie without it
 * inherits whatever the browser has decided this year.
 */
final class DeviceCookie {

    /** Named beside JCFSESSION in application.properties so the pair is recognisable. */
    static final String NAME = "JCFDEVICE";

    private static final String VERSION = "v1";
    private static final String PATH = "/";
    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceCookie() {
    }

    /** The two halves as the browser sent them. */
    record Presented(String selector, String secret) {
    }

    /**
     * 128 bits for the handle and 256 for the credential. The handle only has to be
     * unique and unguessable enough that nobody can enumerate the table; the secret
     * is the thing a stolen database must not yield, so it gets the full 256 bits
     * that make its SHA-256 not worth attacking.
     */
    static String mintSelector() {
        return random(16);
    }

    static String mintSecret() {
        return random(32);
    }

    static String valueOf(String selector, String secret) {
        return VERSION + "." + selector + "." + secret;
    }

    /**
     * Answers empty for anything that is not one of our own cookies, including a
     * value from an older format. A malformed cookie is not an attack worth
     * reporting, it is a browser holding something we no longer issue.
     */
    static Optional<Presented> parse(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) return Optional.empty();
        for (Cookie cookie : request.getCookies()) {
            if (!NAME.equals(cookie.getName())) continue;
            String value = cookie.getValue();
            if (value == null) continue;
            String[] parts = value.split("\\.");
            if (parts.length != 3 || !VERSION.equals(parts[0])) continue;
            if (parts[1].isEmpty() || parts[2].isEmpty()) continue;
            return Optional.of(new Presented(parts[1], parts[2]));
        }
        return Optional.empty();
    }

    static void write(HttpServletResponse response, String value, Duration maxAge, boolean secure) {
        if (response == null) return;
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(maxAge)
                .build()
                .toString());
    }

    /**
     * Expires the cookie with the same attributes it was written with. A browser
     * matches a deletion to an existing cookie by name, domain and path, so a
     * clear that forgets the path leaves the original in place and the person is
     * asked to sign in on every request for as long as it lives.
     */
    static void clear(HttpServletResponse response, boolean secure) {
        if (response == null) return;
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(Duration.ZERO)
                .build()
                .toString());
    }

    private static String random(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
