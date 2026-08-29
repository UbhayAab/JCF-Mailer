package com.jarurat.mailer.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The measurement behind the design of {@link SessionApi}, taken on a real Tomcat
 * over real HTTP, because it cannot be taken anywhere else.
 *
 * The claim is that the obvious implementation of this endpoint is impossible: that
 * an authenticated GET resets the very clock it would be reporting, so a page polling
 * it would hold the session open indefinitely and the countdown would tick towards a
 * moment that never arrived. Two things do it, neither of them ours. Spring Security
 * has to read the SecurityContext out of the session to decide whether the caller may
 * have the endpoint at all, and that read calls request.getSession(false), which is
 * where StandardSession.access() lives. Tomcat's CoyoteAdapter then calls
 * request.getSession(false) once more at the end of every single request, on purpose.
 *
 * MockMvc cannot show any of this: MockHttpSession is a map with getters and has no
 * access clock at all. So this test signs in over the wire, reads the container's own
 * lastAccessedTime through a probe, and asserts both halves of the sentence at once:
 * the container's clock moves across two of our reads, and the deadline we report
 * does not.
 *
 * The probe returns getLastAccessedTime(), which during any request is the access
 * time of the request before it. That is exactly the reading needed, and it is why
 * the probe is called after the request being measured rather than during it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jarurat.mail.jmap-url=http://127.0.0.1:1/jmap/",
                // Set here rather than read from application.properties, because
                // src/test/resources/application.properties has the same resource
                // name and replaces the main file on the test classpath rather than
                // adding to it. Nothing from the production file is loaded during a
                // test run, which is also why the session cookie is JSESSIONID here
                // and JCFSESSION on the box. Fifteen minutes is a value this test
                // owns; production runs eight hours.
                "server.servlet.session.timeout=15m"
        })
class SessionClockTest {

    static final String PROBE = "/api/session-clock-probe";

    /** Matches the timeout property above. */
    private static final long TIMEOUT_SECONDS = 900L;

    @LocalServerPort
    private int port;

    @Value("${admin.email}")
    private String ownerEmail;

    @Value("${admin.password}")
    private String ownerPassword;

    private HttpClient http;

    /**
     * Cookies are kept by hand rather than with java.net.CookieManager. The session
     * cookie is issued Secure, which that manager then refuses to send back over
     * plain http, and its domain matching has long-standing trouble with a host that
     * has no dot in it. Both would show up here as a login that appeared to work and
     * a session that was never carried, which is a confusing way to fail a test about
     * session clocks.
     */
    private final Map<String, String> jar = new LinkedHashMap<>();

    @BeforeEach
    void signIn() throws Exception {
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();

        send(HttpRequest.newBuilder(uri("/login")).GET());
        String token = jar.get("XSRF-TOKEN");
        assertThat(token).as("the console chain issues the token eagerly").isNotNull();

        String form = "email=" + enc(ownerEmail) + "&password=" + enc(ownerPassword)
                + "&_csrf=" + enc(token);
        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));

        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location").orElse("")).doesNotContain("error");
        assertThat(jar.keySet().stream().anyMatch(k -> k.endsWith("SESSIONID") || k.equals("JCFSESSION")))
                .as("a session cookie came back from the login: %s", jar.keySet())
                .isTrue();
    }

    @Test
    @DisplayName("the deadline stands still across repeated reads")
    void readingItDoesNotSpendIt() throws Exception {
        long first = SessionApiTest.number(body(HttpRequest.newBuilder(uri(SessionApi.PATH)).GET()), "expiresAt");
        Thread.sleep(1200);
        long second = SessionApiTest.number(body(HttpRequest.newBuilder(uri(SessionApi.PATH)).GET()), "expiresAt");

        // Nothing else is asked for in between, deliberately: the probe below is an
        // ordinary request and would move this, correctly, by however long it took.
        assertThat(second - first)
                .as("the deadline reported by %s across two reads 1.2s apart", SessionApi.PATH)
                .isZero();
    }

    @Test
    @DisplayName("the container's own clock does move, on that very same read")
    void theContainerClockCannotBeTheAnswer() throws Exception {
        // Two probes back to back, so the second one reports an access time from a
        // moment ago and is a tight baseline rather than a reading from the login.
        probe();
        long baseline = probe();

        Thread.sleep(1200);

        // The read under test, and then a probe reporting the access time it left
        // behind. Nothing else happens in this window, so if the read had genuinely
        // not touched the session, the probe would still be reporting the baseline.
        body(HttpRequest.newBuilder(uri(SessionApi.PATH)).GET());
        long after = probe();

        assertThat(after - baseline)
                .as("Tomcat's lastAccessedTime moved by this much across one GET of %s",
                        SessionApi.PATH)
                .isGreaterThanOrEqualTo(1200L);
    }

    @Test
    @DisplayName("the configured session timeout is what reaches the browser")
    void theConfiguredWindowIsWhatIsReported() throws Exception {
        String state = body(HttpRequest.newBuilder(uri(SessionApi.PATH)).GET());

        // server.servlet.session.timeout, arriving through the container rather than
        // through a second copy of the number in Java. Change that property and this
        // moves with it, which is the whole reason the endpoint asks the session
        // rather than reading a constant.
        assertThat(SessionApiTest.number(state, "timeoutSeconds")).isEqualTo(TIMEOUT_SECONDS);
        assertThat(SessionApiTest.number(state, "remainingSeconds"))
                .isBetween(TIMEOUT_SECONDS - 30, TIMEOUT_SECONDS);
        assertThat(SessionApiTest.number(state, "warnSeconds")).isEqualTo(300L);
    }

    /* ---------- helpers ---------- */

    /** The container's own lastAccessedTime, which during any request is the access
     *  time of the request before it. */
    private long probe() throws Exception {
        return Long.parseLong(body(HttpRequest.newBuilder(uri(PROBE)).GET()).trim());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String body(HttpRequest.Builder builder) throws Exception {
        HttpResponse<String> response = send(builder);
        assertThat(response.statusCode()).as("%s", response.uri()).isEqualTo(200);
        return response.body();
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        if (!jar.isEmpty()) {
            builder.header("Cookie", jar.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        List<String> set = response.headers().allValues("set-cookie");
        for (String header : set) {
            int semi = header.indexOf(';');
            String pair = semi < 0 ? header : header.substring(0, semi);
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (value.isEmpty()) jar.remove(name); else jar.put(name, value);
        }
        return response;
    }

    /**
     * Reports the container's own lastAccessedTime, which is the number this whole
     * class exists to look at and which nothing in the application exposes.
     */
    @TestConfiguration
    static class Probe {

        /*
         * Declared as a member component rather than through a @Bean method. A nested
         * class annotated @RestController inside a configuration class is already
         * registered by the configuration parser, so a @Bean returning one as well
         * produced two beans carrying the same mapping and took the whole context
         * down with "Ambiguous mapping".
         */
        @RestController
        static class SessionClockProbe {

            @GetMapping(PROBE)
            public String lastAccessed(HttpServletRequest request) {
                HttpSession session = request.getSession(false);
                return session == null ? "0" : String.valueOf(session.getLastAccessedTime());
            }
        }
    }
}
