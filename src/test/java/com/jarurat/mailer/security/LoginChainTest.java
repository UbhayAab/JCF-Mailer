package com.jarurat.mailer.security;

import com.jarurat.mailer.webmail.MailboxAccess;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The login page and the login post as they are actually wired, rather than as the
 * unit tests assume they are wired.
 *
 * Four things here can only be got wrong at assembly time and are invisible until
 * somebody hits them on the live box: whether the rate limiter is in the chain at all
 * and slows an over-budget address rather than refusing it, whether the refusal it
 * does still hand out is the designed page, whether a failed login quietly mints a
 * session, and whether the hidden field that carries the Campaign Studio choice
 * through the post renders at all. The two template ones matter most: a Thymeleaf
 * expression that does not resolve takes the page down, which is a far worse outcome
 * than the bug the markup was added to fix, and on the refusal page nobody would ever
 * find out, because the only person who sees it has already been refused.
 *
 * The mail server is pointed at a closed port. Every mailbox probe therefore fails
 * immediately with a transport error, which MailboxAccess.accepts turns into a plain
 * wrong-password answer, so these run at full speed and touch nothing.
 */
@SpringBootTest(properties = "jarurat.mail.jmap-url=http://127.0.0.1:1/jmap/")
class LoginChainTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private LoginRateLimiter limiter;

    /**
     * Spied rather than mocked, so the real chain runs and only the one call this
     * test is about is observed.
     */
    @MockitoSpyBean
    private MailboxAccess mailboxes;

    @Value("${admin.email}")
    private String ownerEmail;

    @Value("${admin.password}")
    private String ownerPassword;

    private MockMvc mvc;
    private Cookie csrfCookie;

    @BeforeEach
    void wireTheRealChain() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        limiter.reset();

        // The console chain issues the token eagerly on any request, and the plain
        // request handler expects the raw cookie value back, so this is exactly what
        // the browser sends.
        csrfCookie = mvc.perform(get("/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
    }

    @Test
    @DisplayName("the login page renders, and carries the desktop choice only when one was made")
    void theDesktopFlagIsCarriedOnTheForm() throws Exception {
        String plain = mvc.perform(get("/login"))
                .andReturn().getResponse().getContentAsString();
        assertThat(plain).contains("name=\"email\"");
        assertThat(plain).doesNotContain("name=\"desktop\"");

        String chosen = mvc.perform(get("/login").param("desktop", "1"))
                .andReturn().getResponse().getContentAsString();
        assertThat(chosen).contains("name=\"desktop\"");
        assertThat(chosen).contains("value=\"1\"");
    }

    @Test
    @DisplayName("a wrong password redirects to /login?error and mints no session")
    void aFailedLoginDoesNotMintASession() throws Exception {
        // SimpleUrlAuthenticationFailureHandler creates a session purely to store an
        // exception no template reads, which turned one unauthenticated post into an
        // eight hour allocation and handed anybody guessing passwords a way to grow
        // the session store while they guessed.
        MvcResult result = login("nobody@jarurat.care");

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("guessing at one address is slowed down and never refused")
    void theLimiterIsActuallyInTheChain() throws Exception {
        for (int i = 0; i < LoginRateLimiter.PER_ADDRESS; i++) {
            assertThat(status("victim@jarurat.care")).as("attempt %d", i + 1).isEqualTo(302);
        }

        long startedAt = System.nanoTime();
        int status = status("victim@jarurat.care");
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        // The one property the whole redesign turns on, asserted on the assembled
        // chain rather than on the limiter alone: the eleventh attempt is delayed and
        // still processed. A 429 here would mean the correct password is refused too,
        // and anybody who knew a staff address could hold that person out of their own
        // mail for ten cheap requests every fifteen minutes.
        assertThat(status).isEqualTo(302);
        assertThat(millis).isGreaterThanOrEqualTo(LoginRateLimiter.FIRST_DELAY_MILLIS - 25);
        // Somebody else is unaffected and not delayed at all.
        assertThat(status("colleague@jarurat.care")).isEqualTo(302);
    }

    @Test
    @DisplayName("a client over its cap gets the designed page, keyed on the peer nginx saw")
    void theClientCapIsRefusedWithTheDesignedPage() throws Exception {
        // Spent through the bean rather than over HTTP, because thirty logins here are
        // thirty bcrypt comparisons at strength twelve and this test is about the
        // wiring rather than about the counting, which LoginRateLimiterTest covers.
        for (int i = 0; i < LoginRateLimiter.PER_CLIENT; i++) {
            limiter.reserve("person" + i + "@jarurat.care", "203.0.113.9");
        }

        // Exactly what nginx sends: whatever the caller claimed, then the address
        // nginx itself saw. Only the last element is the real peer, and burning that
        // key has to be what refuses this request. Reading the first element instead,
        // which this code used to do, answers 302 here.
        MvcResult refused = mvc.perform(post("/login")
                        .cookie(csrfCookie)
                        .header("X-Forwarded-For", "198.51.100.7, 203.0.113.9")
                        .header(ClientIp.TRUSTED_HEADER, "203.0.113.9")
                        .param("_csrf", csrfCookie.getValue())
                        .param("email", "someone@jarurat.care")
                        .param("password", "guess"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(refused.getResponse().getRedirectedUrl()).isNull();
        // MockMvc records the forward rather than rendering it, so this is where the
        // 429 stops being a paragraph of plain text and becomes the designed page.
        assertThat(refused.getResponse().getForwardedUrl()).isEqualTo(TooManyRequestsPage.PATH);
    }

    @Test
    @DisplayName("the refusal page renders, and says what it is")
    void theRefusalPageIsARealPage() throws Exception {
        // A Thymeleaf expression that does not resolve takes this page down, and the
        // only person who ever sees it is somebody who has already been refused, so a
        // broken template here would be invisible until exactly the wrong moment.
        MvcResult result = mvc.perform(get(TooManyRequestsPage.PATH)).andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(body).contains("Too many attempts");
        assertThat(body).contains("/css/style.css");
        // Section 6 of the UI specification: an icon is a sprite symbol and never a
        // character the operating system happens to have a font for.
        assertThat(body).contains("<use href=\"#i-shield\"/>");
    }

    @Test
    @DisplayName("signing out drops the mailbox credential, not just this browser's pointer to it")
    void signOutClosesTheMailbox() throws Exception {
        // MailboxAccess.close says sign out has to mean the password is gone from
        // this process, and nothing on the logout path was calling it: invalidating
        // the session dropped the pin and left the plaintext secret resident in
        // MailCredentialStore for the life of the JVM. It also has to run before the
        // session is invalidated, because the mailbox to forget is read off it.
        MvcResult signedIn = mvc.perform(post("/login")
                        .cookie(csrfCookie)
                        .param("_csrf", csrfCookie.getValue())
                        .param("email", ownerEmail)
                        .param("password", ownerPassword))
                .andReturn();
        HttpSession session = signedIn.getRequest().getSession(false);
        assertThat(signedIn.getResponse().getRedirectedUrl()).isEqualTo("/app");
        assertThat(session).isNotNull();

        MvcResult out = mvc.perform(get("/logout")
                        .cookie(csrfCookie)
                        .session((MockHttpSession) session))
                .andReturn();

        assertThat(out.getResponse().getRedirectedUrl()).isEqualTo("/login?loggedOut");
        verify(mailboxes).close(any(), any());
    }

    private int status(String address) throws Exception {
        return login(address).getResponse().getStatus();
    }

    private MvcResult login(String address) throws Exception {
        return mvc.perform(post("/login")
                        .cookie(csrfCookie)
                        .param("_csrf", csrfCookie.getValue())
                        .param("email", address)
                        .param("password", "guess"))
                .andReturn();
    }
}
