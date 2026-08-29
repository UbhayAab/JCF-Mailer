package com.jarurat.mailer.device;

import com.jarurat.mailer.security.ApiKeyHasher;
import com.jarurat.mailer.webmail.MailboxAccess;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * That the filter is actually in the chain, and that a cookie alone is enough to be
 * signed in, asked of the application as it is assembled rather than as this package
 * assumes it is assembled.
 *
 * Both halves of that are only ever wrong at assembly time and are invisible until
 * somebody reaches for their phone. A filter that is written, tested and never
 * registered degrades to exactly the behaviour it was meant to replace, and would be
 * reported as "the feature was never built" rather than as a bug. So this asserts the
 * registration itself, and then asserts the one thing the registration is for: a
 * request carrying nothing but a device cookie is answered as a signed-in person.
 *
 * The mail server is pointed at a closed port, as in LoginChainTest, and MailboxAccess
 * is replaced, because opening a mailbox for real needs Stalwart. What is exercised
 * here is the wiring, the token and the authentication; DeviceTokenServiceTest covers
 * what happens to the rows and PersistentDeviceFilterTest covers what happens when the
 * mailbox refuses.
 */
@SpringBootTest(properties = "jarurat.mail.jmap-url=http://127.0.0.1:1/jmap/")
class DeviceChainWiringTest {

    private static final String MAILBOX = "priya@jarurat.care";
    private static final String PASSWORD = "the-mailbox-password";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private DeviceTokenRepository repository;

    @MockitoBean
    private MailboxAccess mailboxes;

    private MockMvc mvc;

    @BeforeEach
    void wireTheRealChain() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters((Filter) springSecurityFilterChain)
                .build();
        repository.deleteAll();
    }

    @Test
    @DisplayName("the filter is on the console chain, once, and nowhere else")
    void theFilterIsRegistered() {
        assertThat(springSecurityFilterChain.getFilters("/mail"))
                .filteredOn(PersistentDeviceFilter.class::isInstance)
                .hasSize(1);

        // The machine API is stateless and API key only. A device cookie must not be a
        // credential there.
        assertThat(springSecurityFilterChain.getFilters("/api/v1/anything"))
                .noneMatch(PersistentDeviceFilter.class::isInstance);

        // And it is not a bean, because Spring Boot registers Filter beans with the
        // servlet container as well, which would run it a second time outside every
        // security chain.
        assertThat(context.getBeanNamesForType(PersistentDeviceFilter.class)).isEmpty();
    }

    @Test
    @DisplayName("a cookie alone signs a phone in, and the token it used is spent")
    void aCookieAloneIsEnough() throws Exception {
        String selector = DeviceCookie.mintSelector();
        String secret = DeviceCookie.mintSecret();
        Instant now = Instant.now();
        repository.save(new DeviceToken(selector, ApiKeyHasher.sha256(secret), "family-1", MAILBOX,
                DeviceCredentialCipher.seal(secret, MAILBOX, PASSWORD), "iPhone Safari",
                now, now, "203.0.113.9", now.plus(Duration.ofDays(180))));

        MvcResult result = mvc.perform(get("/api/devices")
                        .cookie(new Cookie(DeviceCookie.NAME, DeviceCookie.valueOf(selector, secret))))
                .andReturn();

        // 200 rather than the 401 an unauthenticated caller gets below: the session was
        // rebuilt from the cookie before the authorization rules ran.
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("iPhone Safari");

        Cookie rotated = result.getResponse().getCookie(DeviceCookie.NAME);
        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotEqualTo(DeviceCookie.valueOf(selector, secret));
        assertThat(rotated.isHttpOnly()).isTrue();
        assertThat(rotated.getMaxAge()).isGreaterThan(30 * 24 * 60 * 60);

        assertThat(repository.findBySelector(selector).orElseThrow().getSupersededAt()).isNotNull();
    }

    @Test
    @DisplayName("signing out takes the device with it, so the cookie cannot sign straight back in")
    void signingOutRevokesTheDevice() throws Exception {
        String selector = DeviceCookie.mintSelector();
        String secret = DeviceCookie.mintSecret();
        Cookie cookie = new Cookie(DeviceCookie.NAME, DeviceCookie.valueOf(selector, secret));
        Instant now = Instant.now();
        repository.save(new DeviceToken(selector, ApiKeyHasher.sha256(secret), "family-2", MAILBOX,
                DeviceCredentialCipher.seal(secret, MAILBOX, PASSWORD), "iPhone Safari",
                now, now, "203.0.113.9", now.plus(Duration.ofDays(180))));

        mvc.perform(get("/logout").cookie(cookie)).andReturn();

        // Without this, Sign out would drop the password from the heap and leave a
        // cookie on the phone that silently signed it back in on the next request,
        // which is a button that does not do what it says.
        assertThat(repository.findByFamilyId("family-2")).isEmpty();
    }

    @Test
    @DisplayName("without a cookie the same request is refused")
    void nothingIsGrantedWithoutOne() throws Exception {
        assertThat(mvc.perform(get("/api/devices")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }
}
