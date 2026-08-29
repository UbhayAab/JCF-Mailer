package com.jarurat.mailer.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These exist because three call sites each answered "who sent this" for themselves
 * and all three were wrong the same way. The value feeds the login rate limiter, the
 * OTP burst limit and the source address on every audit row, so a caller who can
 * choose it can lift their own limits, hold somebody else over theirs, and sign the
 * evidence with a name of their choosing.
 */
class ClientIpTest {

    @Test
    void theRealIpHeaderIsWhatIsRead() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "203.0.113.9");
        request.setRemoteAddr("127.0.0.1");

        assertThat(ClientIp.of(request)).isEqualTo("203.0.113.9");
    }

    /**
     * The one that matters. nginx appends the peer to X-Forwarded-For, so its first
     * element is whatever the caller sent, and it overwrites X-Real-IP outright. A
     * caller who forges both must not be able to move their own key: if this returns
     * the forged value the limiter can be rotated for unlimited guesses.
     */
    @Test
    void aForgedForwardedHeaderCannotChooseTheKey() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.9");
        request.addHeader("X-Real-IP", "203.0.113.9");
        request.setRemoteAddr("127.0.0.1");

        assertThat(ClientIp.of(request)).isEqualTo("203.0.113.9");
    }

    /**
     * Rotating the forged element must not mint a fresh bucket. Two requests from one
     * machine have to land on one key however the caller decorates them, or the cap
     * is not a cap.
     */
    @Test
    void rotatingTheForgedElementDoesNotMintFreshKeys() {
        String first = ClientIp.of(requestClaiming("1.1.1.1", "198.51.100.4"));
        String second = ClientIp.of(requestClaiming("2.2.2.2", "198.51.100.4"));
        String third = ClientIp.of(requestClaiming("3.3.3.3, 4.4.4.4", "198.51.100.4"));

        assertThat(first).isEqualTo("198.51.100.4");
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    /** Nothing in front of the application, as in a local run. */
    @Test
    void theSocketAddressIsTheFallback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.55");

        assertThat(ClientIp.of(request)).isEqualTo("192.0.2.55");
    }

    /**
     * Never null and never a null-ish string, because callers key maps on it. An
     * unattributable request shares one bucket rather than escaping the limit.
     */
    @Test
    void anUnattributableRequestIsAnEmptyStringAndNotNull() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        assertThat(ClientIp.of(request)).isEmpty();
        assertThat(ClientIp.of(null)).isEmpty();
    }

    @Test
    void aBlankHeaderFallsThroughRatherThanReturningBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "   ");
        request.setRemoteAddr("192.0.2.7");

        assertThat(ClientIp.of(request)).isEqualTo("192.0.2.7");
    }

    /** The limiter keys on this, so it must agree with itself character for character. */
    @Test
    void theLimiterKeyIsTheSameAnswer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "9.9.9.9, 198.51.100.4");
        request.addHeader("X-Real-IP", "198.51.100.4");

        assertThat(LoginRateLimiter.clientKey(request)).isEqualTo(ClientIp.of(request));
        assertThat(LoginRateLimiter.clientKey(request)).isEqualTo("198.51.100.4");
    }

    private static MockHttpServletRequest requestClaiming(String forged, String realPeer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", forged + ", " + realPeer);
        request.addHeader("X-Real-IP", realPeer);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
