package com.jarurat.mailer.controllers;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The session lifetime endpoints, on the chain as it is actually assembled.
 *
 * The property the whole feature turns on is that reading the countdown does not
 * spend any of it. It cannot be checked by reading the source, because the thing that
 * would break it is not in the source: Spring Security and Tomcat both touch the
 * session on the way past, before and after anything this file wrote runs.
 * SessionClockTest, at the bottom, measures that on a real Tomcat. This class holds
 * the behaviour that MockMvc can settle honestly.
 *
 * The mail server is pointed at a closed port, exactly as LoginChainTest does, so the
 * background mailbox probe that follows a successful console login fails immediately
 * instead of waiting on a socket.
 */
@SpringBootTest(properties = "jarurat.mail.jmap-url=http://127.0.0.1:1/jmap/")
class SessionApiTest {

    /**
     * A tidy round window for the arithmetic. It has to be set by hand because
     * MockHttpSession is not a container and does not read
     * server.servlet.session.timeout; in production the same number arrives from
     * there as 28800.
     */
    private static final int TIMEOUT_SECONDS = 600;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private SessionApi.SessionActivityFilter activityFilter;

    @Autowired
    private SpringTemplateEngine templates;

    @Value("${admin.email}")
    private String ownerEmail;

    @Value("${admin.password}")
    private String ownerPassword;

    private MockMvc mvc;
    private Cookie csrfCookie;

    @BeforeEach
    void wireTheRealChain() throws Exception {
        // Security first and the activity filter behind it, which is the order the
        // FilterRegistrationBean produces in the running application and the reason a
        // refused request never counts as somebody being there.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain, activityFilter)
                .build();
        csrfCookie = mvc.perform(get("/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
    }

    @Test
    @DisplayName("reading the countdown does not spend any of it")
    void aReadNeverMovesTheDeadline() throws Exception {
        MockHttpSession session = signedInSession();

        long first = expiresAt(session);
        Thread.sleep(1100);
        long second = expiresAt(session);
        Thread.sleep(1100);
        long third = expiresAt(session);

        // The whole point of the endpoint. If reading it refreshed the session, these
        // would climb by a second each time and a page polling it would hold the
        // session open for as long as the tab stayed open.
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(remainingSeconds(session)).isLessThan(TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("any other request does push the deadline out")
    void realActivityMovesTheDeadline() throws Exception {
        MockHttpSession session = signedInSession();
        long before = expiresAt(session);

        Thread.sleep(1100);
        // An ordinary page load. Nothing about it knows this feature exists.
        mvc.perform(get("/app").session(session)).andReturn();

        assertThat(expiresAt(session)).isGreaterThanOrEqualTo(before + 1000);
    }

    @Test
    @DisplayName("the deadline is the container's own window counted from the last real request")
    void theWindowComesFromTheContainer() throws Exception {
        MockHttpSession session = signedInSession();
        long activeAt = System.currentTimeMillis() - 30_000L;
        session.setAttribute(SessionApi.ACTIVE_AT, activeAt);

        String body = ok(session);

        // Restated nowhere: server.servlet.session.timeout is the only place the
        // number lives, and the endpoint reads it back off the session.
        assertThat(number(body, "expiresAt")).isEqualTo(activeAt + TIMEOUT_SECONDS * 1000L);
        assertThat(number(body, "timeoutSeconds")).isEqualTo(TIMEOUT_SECONDS);
        assertThat(number(body, "remainingSeconds")).isBetween(TIMEOUT_SECONDS - 32L, TIMEOUT_SECONDS - 29L);
        assertThat(body).contains("\"authenticated\":true").contains("\"expired\":false");
    }

    @Test
    @DisplayName("past the deadline it answers 401, and leaves the session for /logout to end")
    void anExpiredSessionIsReportedAndNotDestroyed() throws Exception {
        MockHttpSession session = signedInSession();
        session.setAttribute(SessionApi.ACTIVE_AT, System.currentTimeMillis() - (TIMEOUT_SECONDS + 5) * 1000L);

        MvcResult result = mvc.perform(get(SessionApi.PATH).session(session)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"authenticated\":false")
                .contains("\"expired\":true");
        // Deliberately still there. A GET that destroys a session is a surprise, and
        // /logout is the path that also drops the mailbox password out of the process.
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    @DisplayName("extend buys another full window")
    void extendRefreshesTheSession() throws Exception {
        MockHttpSession session = signedInSession();
        session.setAttribute(SessionApi.ACTIVE_AT, System.currentTimeMillis() - 120_000L);
        assertThat(remainingSeconds(session)).isLessThan(TIMEOUT_SECONDS - 100);

        MvcResult result = mvc.perform(post(SessionApi.PATH + "/extend")
                .session(session)
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(number(result.getResponse().getContentAsString(), "remainingSeconds"))
                .isEqualTo(TIMEOUT_SECONDS);
        assertThat(remainingSeconds(session)).isEqualTo(TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("extend is refused once it is over, so the sentence the person just read stays true")
    void extendCannotReviveAnEndedSession() throws Exception {
        MockHttpSession session = signedInSession();
        long dead = System.currentTimeMillis() - (TIMEOUT_SECONDS + 5) * 1000L;
        session.setAttribute(SessionApi.ACTIVE_AT, dead);

        MvcResult result = mvc.perform(post(SessionApi.PATH + "/extend")
                .session(session)
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(session.getAttribute(SessionApi.ACTIVE_AT)).isEqualTo(dead);
    }

    @Test
    @DisplayName("extend is a write, so it is behind the CSRF token")
    void extendNeedsTheToken() throws Exception {
        MockHttpSession session = signedInSession();

        MvcResult result = mvc.perform(post(SessionApi.PATH + "/extend").session(session)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("the mail-prefixed copy answers the same thing, which is what the phone mailbox reads")
    void theMailAliasIsLive() throws Exception {
        MockHttpSession session = signedInSession();

        MvcResult result = mvc.perform(get(SessionApi.MAIL_PATH).session(session)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(number(result.getResponse().getContentAsString(), "expiresAt")).isPositive();
        // The reason it exists: SecurityConfig closes /api/session to a session bought
        // with a mailbox password, and /mail is the surface most of the organisation
        // has. If this ever fails because the alias was removed, "/api/session/**"
        // belongs in SecurityConfig.MAIL_ONLY_PATHS instead.
    }

    @Test
    @DisplayName("nobody signed in gets a 401 rather than a countdown")
    void aStrangerGetsNothing() throws Exception {
        MvcResult result = mvc.perform(get(SessionApi.PATH)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("the activity filter counts everything except the four session endpoints")
    void theFilterSkipsItsOwnEndpoints() throws Exception {
        for (String path : SessionApi.OWN_PATHS) {
            assertThat(stamped(path)).as("%s must not count as activity", path).isNull();
        }
        // A prefix test would have excused this one too, and the extend endpoint is
        // the single request in the set that is supposed to move the deadline.
        assertThat(SessionApi.OWN_PATHS).contains(SessionApi.PATH + "/extend");

        assertThat(stamped("/app")).isNotNull();
        assertThat(stamped("/api/campaigns")).isNotNull();
        assertThat(stamped("/js/session.js")).isNotNull();
        // Neighbouring paths are ordinary traffic, not a session read.
        assertThat(stamped("/api/session-report")).isNotNull();
        assertThat(stamped("/api/mail/status")).isNotNull();
    }

    @Test
    @DisplayName("the filter never mints a session for somebody who has none")
    void theFilterDoesNotCreateSessions() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        activityFilter.doFilter(request, new MockHttpServletResponse(), (rq, rs) -> { });

        assertThat(request.getSession(false)).isNull();
    }

    @Test
    @DisplayName("the fragment resolves, so the one line include cannot take a page down")
    void theFragmentIsRenderable() {
        // No page includes it yet, and the include is meant to be a one line change
        // somebody else makes later. A fragment that did not resolve would take that
        // page down at render time, which is a far worse outcome than the missing
        // warning it was written to fix, so it is proved here instead of on the box.
        // process(name, selectors, context) rather than the "a :: b" spelling: that
        // form is a fragment expression a template writes, not a template name the
        // engine resolves, and passing it here only produces "template might not
        // exist". This is the same resolution a th:replace performs.
        String rendered = templates.process("fragments/session", Set.of("surface"), new Context());

        assertThat(rendered).contains("<script src=\"/js/session.js\"></script>");
        // Nothing server-rendered and nothing to resolve: the whole surface is built
        // by the script, on pages that mostly do not load style.css.
        assertThat(rendered).doesNotContain("th:").doesNotContain("${");
    }

    /* ---------- helpers ---------- */

    /**
     * A real login through the real chain, followed by one ordinary page load.
     *
     * The page load is not decoration. A successful login post never reaches the
     * filters behind the authentication filter, so nothing has stamped the session
     * yet; in the browser the stamp arrives with the very page that carries the
     * script. Reproducing that here keeps the tests measuring the endpoint rather
     * than the fallback.
     */
    private MockHttpSession signedInSession() throws Exception {
        MvcResult login = mvc.perform(post("/login")
                .cookie(csrfCookie)
                .param("_csrf", csrfCookie.getValue())
                .param("email", ownerEmail)
                .param("password", ownerPassword)).andReturn();
        assertThat(login.getResponse().getRedirectedUrl()).doesNotContain("error");

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        session.setMaxInactiveInterval(TIMEOUT_SECONDS);

        mvc.perform(get("/app").session(session)).andReturn();
        assertThat(session.getAttribute(SessionApi.ACTIVE_AT)).isNotNull();
        return session;
    }

    /** Runs one request through the filter alone and reports what it stamped. */
    private Object stamped(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        activityFilter.doFilter(request, new MockHttpServletResponse(), (rq, rs) -> { });
        return session.getAttribute(SessionApi.ACTIVE_AT);
    }

    private String ok(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get(SessionApi.PATH).session(session)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private long expiresAt(MockHttpSession session) throws Exception {
        return number(ok(session), "expiresAt");
    }

    private long remainingSeconds(MockHttpSession session) throws Exception {
        return number(ok(session), "remainingSeconds");
    }

    /**
     * Pulled out with a regular expression rather than a JSON parser so the assertion
     * is against the bytes that go over the wire, field name included. A record
     * component quietly renamed would pass a mapped-object test and break the page.
     */
    static long number(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(-?\\d+)").matcher(json);
        assertThat(m.find()).as("field %s in %s", field, json).isTrue();
        return Long.parseLong(m.group(1));
    }
}
