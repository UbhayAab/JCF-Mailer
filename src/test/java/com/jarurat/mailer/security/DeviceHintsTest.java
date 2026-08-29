package com.jarurat.mailer.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a sign in lands is decided from these two answers, on both sides of the
 * login, so the two sides have to agree by construction. A drift between them shows
 * up as a redirect loop, which is the one failure a user cannot work around.
 */
class DeviceHintsTest {

    private static final String IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    private static final String ANDROID_PHONE =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final String ANDROID_TABLET =
            "Mozilla/5.0 (Linux; Android 13; SM-X700) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";
    private static final String WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";
    private static final String IPAD_OS =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.5 Safari/605.1.15";

    @Test
    @DisplayName("the client hint wins over the user agent, in both directions")
    void hintBeatsUserAgent() {
        assertThat(DeviceHints.isPhone(request(WINDOWS, "?1"))).isTrue();
        assertThat(DeviceHints.isPhone(request(IPHONE, "?0"))).isFalse();
    }

    @Test
    @DisplayName("a hint that is neither ?1 nor ?0 is a rewrite, so the user agent decides")
    void malformedHintFallsBack() {
        assertThat(DeviceHints.isPhone(request(IPHONE, "true"))).isTrue();
        assertThat(DeviceHints.isPhone(request(WINDOWS, "true"))).isFalse();
    }

    @Test
    @DisplayName("without a hint, phones are recognised and laptops and tablets are not")
    void userAgentFallback() {
        assertThat(DeviceHints.isPhone(request(IPHONE, null))).isTrue();
        assertThat(DeviceHints.isPhone(request(ANDROID_PHONE, null))).isTrue();
        assertThat(DeviceHints.isPhone(request(WINDOWS, null))).isFalse();
        // An Android tablet carries "Android" without "Mobile", and iPadOS reports a
        // desktop Safari string. Both are console sized and both must stay on /app.
        assertThat(DeviceHints.isPhone(request(ANDROID_TABLET, null))).isFalse();
        assertThat(DeviceHints.isPhone(request(IPAD_OS, null))).isFalse();
    }

    @Test
    @DisplayName("no headers at all is not a phone")
    void nothingIsNotAPhone() {
        assertThat(DeviceHints.isPhone(new MockHttpServletRequest())).isFalse();
        assertThat(DeviceHints.isPhone(null)).isFalse();
    }

    @Test
    @DisplayName("desktop=1 is remembered for the session, and desktop=0 gives the phone back")
    void escapeHatchIsRemembered() {
        MockHttpSession session = new MockHttpSession();

        MockHttpServletRequest optIn = request(IPHONE, "?1");
        optIn.setSession(session);
        optIn.setParameter("desktop", "1");
        assertThat(DeviceHints.wantsMailbox(optIn)).isFalse();

        // The next request carries no parameter and must still get the console.
        MockHttpServletRequest later = request(IPHONE, "?1");
        later.setSession(session);
        assertThat(DeviceHints.prefersDesktop(later)).isTrue();
        assertThat(DeviceHints.wantsMailbox(later)).isFalse();

        MockHttpServletRequest optOut = request(IPHONE, "?1");
        optOut.setSession(session);
        optOut.setParameter("desktop", "0");
        assertThat(DeviceHints.wantsMailbox(optOut)).isTrue();

        MockHttpServletRequest afterOptOut = request(IPHONE, "?1");
        afterOptOut.setSession(session);
        assertThat(DeviceHints.prefersDesktop(afterOptOut)).isFalse();
    }

    @Test
    @DisplayName("reading the flag never mints a session for an anonymous visitor")
    void readingCreatesNoSession() {
        MockHttpServletRequest anonymous = request(IPHONE, "?1");
        assertThat(DeviceHints.prefersDesktop(anonymous)).isFalse();
        assertThat(anonymous.getSession(false)).isNull();

        MockHttpServletRequest declining = request(IPHONE, "?1");
        declining.setParameter("desktop", "0");
        assertThat(DeviceHints.prefersDesktop(declining)).isFalse();
        assertThat(declining.getSession(false)).isNull();
    }

    @Test
    @DisplayName("a mail-only session lands on the mailbox from any device")
    void mailOnlyAlwaysLandsOnTheMailbox() {
        List<GrantedAuthority> mailOnly = authorities("MAIL_READ", "MAIL_SEND");
        assertThat(LoginLandingHandler.landingFor(mailOnly, request(WINDOWS, "?0"))).isEqualTo("/mail");
        assertThat(LoginLandingHandler.landingFor(mailOnly, request(IPHONE, "?1"))).isEqualTo("/mail");

        // Even with the escape hatch: a mailbox session has no console to escape to.
        MockHttpServletRequest insisting = request(WINDOWS, "?0");
        insisting.setSession(new MockHttpSession());
        insisting.setParameter("desktop", "1");
        assertThat(LoginLandingHandler.landingFor(mailOnly, insisting)).isEqualTo("/mail");
    }

    @Test
    @DisplayName("a console session follows the account, not the device")
    void consoleSessionFollowsTheAccount() {
        List<GrantedAuthority> admin = authorities("MAIL_READ", "MAIL_SEND", "CAMPAIGNS_SEND");
        assertThat(LoginLandingHandler.landingFor(admin, request(IPHONE, "?1"))).isEqualTo("/app");
        assertThat(LoginLandingHandler.landingFor(admin, request(WINDOWS, "?0"))).isEqualTo("/app");

        // "?desktop=1" agrees with the default now rather than overriding it, and is kept
        // because reading it is what records the choice for the rest of the session.
        MockHttpServletRequest onAPhoneWantingTheConsole = request(IPHONE, "?1");
        onAPhoneWantingTheConsole.setSession(new MockHttpSession());
        onAPhoneWantingTheConsole.setParameter("desktop", "1");
        assertThat(LoginLandingHandler.landingFor(admin, onAPhoneWantingTheConsole)).isEqualTo("/app");

        // The mirror switch, for somebody who runs the console but mostly wants mail on
        // a phone. Without it that person would have no way to ask at all.
        MockHttpServletRequest wantingTheMailbox = request(IPHONE, "?1");
        wantingTheMailbox.setSession(new MockHttpSession());
        wantingTheMailbox.setParameter("mailbox", "1");
        assertThat(LoginLandingHandler.landingFor(admin, wantingTheMailbox)).isEqualTo("/mail");
    }

    @Test
    @DisplayName("a role with no mail permission stays on the console even on a phone")
    void viewerIsNeverSentToAPageItCannotOpen() {
        // /mail is gated on MAIL_READ, so routing VIEWER there would answer a phone
        // sign in with a 403 instead of a screen.
        List<GrantedAuthority> viewer = Role.VIEWER.getPermissions().stream()
                .map(p -> (GrantedAuthority) p::name).toList();
        assertThat(Role.VIEWER.can(Permission.MAIL_READ)).isFalse();
        assertThat(LoginLandingHandler.landingFor(viewer, request(IPHONE, "?1"))).isEqualTo("/app");
        assertThat(LoginLandingHandler.landingFor(List.of(), request(IPHONE, "?1"))).isEqualTo("/app");
        assertThat(LoginLandingHandler.landingFor(null, request(IPHONE, "?1"))).isEqualTo("/app");
    }

    private static MockHttpServletRequest request(String userAgent, String mobileHint) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (userAgent != null) request.addHeader("User-Agent", userAgent);
        if (mobileHint != null) request.addHeader("Sec-CH-UA-Mobile", mobileHint);
        return request;
    }

    private static List<GrantedAuthority> authorities(String... names) {
        return Arrays.stream(names).map(n -> (GrantedAuthority) () -> n).toList();
    }
}
