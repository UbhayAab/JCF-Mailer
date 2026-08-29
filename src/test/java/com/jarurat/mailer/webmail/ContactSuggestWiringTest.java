package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.MailCredentialStore;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * GET /api/mail/contacts as it is actually assembled, rather than as the unit tests
 * next door assume it is assembled.
 *
 * Four things about this endpoint can only be got wrong at wiring time and are
 * invisible to a test that constructs the controller by hand: whether a second
 * @RestController on the same /api/mail base is mapped at all, whether the security
 * chain lets a mail-only session reach it, whether the MAIL_READ gate on it actually
 * denies, and whether the promise it makes to the client, that nothing but 200 ever
 * comes out of it, survives contact with a mail server that is not answering. The
 * last one is the reason this file exists at all. The client half is being written
 * against that promise by somebody who cannot see this code, and a promise verified
 * only against a mock is a promise about the mock.
 *
 * The mail server is pointed at a closed port, exactly as LoginChainTest does it, so
 * every JMAP call fails instantly with a transport error and the harvest that sits
 * behind this endpoint fails in the most complete way it can.
 */
@SpringBootTest(properties = "jarurat.mail.jmap-url=http://127.0.0.1:1/jmap/")
class ContactSuggestWiringTest {

    private static final String MAILBOX = "hr@jarurat.care";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private MailCredentialStore credentials;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    }

    /**
     * The credential store is process wide and this context is cached and shared with
     * every other @SpringBootTest, so a mailbox opened here has to be closed here.
     */
    @AfterEach
    void tearDown() {
        credentials.forget(MAILBOX);
    }

    @Test
    @DisplayName("the route is mapped and closed to anybody who is not signed in")
    void theRouteExistsAndIsBehindTheChain() throws Exception {
        // 401 rather than 404 is the whole assertion: a route that was never mapped
        // would sail past the authorization rules and come back as a not found. The
        // console chain answers /api/** with a status and everything else with the
        // login page, which is what the contract tells the client to expect here.
        mvc.perform(get("/api/mail/contacts").param("q", "pri"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
    }

    @Test
    @DisplayName("a session without MAIL_READ is refused, and the handler does not swallow the refusal")
    void theMailReadGateStillDenies() throws Exception {
        // ContactSuggestApi catches RuntimeException and answers 200 with an empty
        // list. AccessDeniedException is a RuntimeException, so without the rethrow in
        // that handler this returns a cheerful 200 and the permission stops meaning
        // anything at all. This is that rethrow, driven.
        mvc.perform(get("/api/mail/contacts").param("q", "pri").session(signedIn("SUBSCRIBERS_READ")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403));
    }

    @Test
    @DisplayName("no mailbox open answers 200 with locked, not the 409 the rest of /api/mail gives")
    void aLockedMailboxIsNotAnInterruption() throws Exception {
        String body = mvc.perform(get("/api/mail/contacts").param("q", "pri").session(signedIn("MAIL_READ")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo("{\"q\":\"pri\",\"locked\":true,\"contacts\":[]}");
    }

    @Test
    @DisplayName("a mail server that is not answering is still a 200 and an empty list")
    void anUnreachableMailServerNeverReachesTheUi() throws Exception {
        // A real open mailbox against a closed port. Everything the harvest tries,
        // the folder list, both folder scans and the identity lookup, fails with a
        // transport error, which is the worst case this endpoint has.
        credentials.remember(MAILBOX, "not-checked-because-nothing-answers");
        MockHttpSession session = signedIn("MAIL_READ");
        session.setAttribute(MailboxAccess.SESSION_KEY, MAILBOX);

        String body = mvc.perform(get("/api/mail/contacts").param("q", "pri").param("limit", "5").session(session))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo("{\"q\":\"pri\",\"locked\":false,\"contacts\":[]}");
    }

    @Test
    @DisplayName("a junk limit is a 200 with the default, not the 400 a bound int would give")
    void aJunkLimitIsNotAnError() throws Exception {
        // MailApiController binds its limit as an int and turns a bad one into a 400
        // through onBadParam. This route takes it as text on purpose, so there is no
        // path at all from a malformed parameter to a status the dropdown has to read.
        mvc.perform(get("/api/mail/contacts").param("q", "pri").param("limit", "not-a-number")
                        .session(signedIn("MAIL_READ")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
    }

    /**
     * A session carrying an already-authenticated context, which is what the chain
     * finds on the second and every later request of a real sign-in. Built by hand
     * because spring-security-test is not a dependency of this project and adding one
     * for a helper is not worth it.
     */
    private static MockHttpSession signedIn(String... authorities) {
        SecurityContext ctx = new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                MAILBOX, null, List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);
        return session;
    }
}
