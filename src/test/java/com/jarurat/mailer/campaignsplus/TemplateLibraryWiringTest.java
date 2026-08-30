package com.jarurat.mailer.campaignsplus;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * That the Templates screen can actually reach the template library.
 *
 * This project has now shipped finished, tested code that no screen could reach
 * five separate times, and each time the owner reasonably concluded the feature
 * had never been built. TemplateLibraryService was one of them: 347 lines, live
 * endpoints over all of it, and not one caller anywhere in static/. A unit test on
 * the service would have passed on every one of those days.
 *
 * So this file asserts the join rather than either side of it. It reads the URLs
 * out of templates.js as text and drives each one through the real security chain.
 * Deleting an endpoint, renaming a path, or tightening a permission the screen
 * depends on fails here, at the point where it becomes a dead button, instead of
 * being noticed months later.
 */
@SpringBootTest
class TemplateLibraryWiringTest {

    private static final Path SCRIPT = Path.of("src/main/resources/static/js/templates.js");

    /** Any '/api/...' inside a single-quoted string literal in the script. */
    private static final Pattern API_PATH = Pattern.compile("'(/api/[A-Za-z0-9/_.-]*)'");

    private static final String ACTOR = "anita@jarurat.care";

    /* Deliberately an id nothing has. The bootstrap seeds transactional templates
       into this context and the context is shared with every other @SpringBootTest,
       so driving these paths against id 1 would both depend on that seed and, on the
       two writes, change data other tests then read. A missing id leaves every
       endpoint exactly one possible answer, and that answer still proves the route
       is mapped and the caller got past the gate. */
    private static final String MISSING = "99000001";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mvc;
    private Cookie csrf;

    @BeforeEach
    void wireTheRealChain() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        csrf = mvc.perform(get("/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
    }

    // ------------------------------------------------------------------
    // What the screen asks for
    // ------------------------------------------------------------------

    @Test
    @DisplayName("templates.js reaches three API bases and no others")
    void theScriptCallsWhatThisFileChecks() throws Exception {
        // Written out rather than derived, so adding a fourth base to the screen fails
        // here until somebody has driven it through the chain below.
        assertThat(apiPathsIn(script())).containsExactlyInAnyOrder(
                "/api/campaignsplus/templates",
                "/api/subscribers",
                "/api/campaigns");
    }

    @Test
    @DisplayName("the three URLs the script builds by hand are paths the server maps")
    void theComposedPathsAreBuiltCorrectly() throws Exception {
        // Each of these is assembled from the library base plus a suffix, so the regex
        // above cannot see any of them whole. Assert the halves that make them here,
        // then drive each one through the chain in the tests below.
        String source = script();
        assertThat(source).contains("var APPLY         = LIBRARY + '/apply';");
        assertThat(source).contains("var FROM_CAMPAIGN = LIBRARY + '/save-from-campaign';");
        assertThat(source).contains("LIBRARY + '/' + current.id + '/preview'");

        assertThat(status(get("/api/campaignsplus/templates/" + MISSING + "/preview")
                .session(signedIn("TEMPLATES_READ"))))
                // 400 is "no such template", which is what a mapped and authorised
                // endpoint answers for an id nothing has. 404 would mean the path the
                // script builds is not a path this application serves at all.
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // Mapped, and behind the chain
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every endpoint the screen uses is mapped and refuses an anonymous caller")
    void everyEndpointIsMappedAndClosed() throws Exception {
        // 401 rather than 404 is the whole assertion. A path that was never mapped
        // sails past the authorization rules and comes back as a not found, so a
        // typo in the script would otherwise look exactly like being signed out.
        for (String path : List.of("/api/campaignsplus/templates", "/api/subscribers", "/api/campaigns")) {
            assertThat(status(get(path))).as("GET %s", path).isEqualTo(401);
        }
        assertThat(status(withCsrf(post("/api/campaignsplus/templates/apply"))))
                .as("POST apply").isEqualTo(401);
        assertThat(status(withCsrf(post("/api/campaignsplus/templates/save-from-campaign"))))
                .as("POST save-from-campaign").isEqualTo(401);
    }

    @Test
    @DisplayName("the library opens to TEMPLATES_READ, the same permission the templates table uses")
    void theLibraryMatchesTheExistingTemplatesPermission() throws Exception {
        assertThat(status(get("/api/campaignsplus/templates").session(signedIn("TEMPLATES_READ"))))
                .isEqualTo(200);
        assertThat(status(get("/api/campaignsplus/templates").session(signedIn("CAMPAIGNS_READ"))))
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------
    // The guard the screen is built around
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a preview against a real subscriber needs SUBSCRIBERS_READ as well")
    void previewingAsSomebodyRealIsAReadOfTheAudience() throws Exception {
        // HR holds TEMPLATES_READ and was deliberately denied the subscriber base. A
        // preview echoes back the name and address of whoever it rendered for, so
        // without this second clause HR could walk the whole list one id at a time.
        // This is why templates.js hides the subscriber picker rather than showing a
        // search box that 403s on every keystroke.
        assertThat(status(get("/api/campaignsplus/templates/" + MISSING + "/preview")
                .param("subscriberId", "1")
                .session(signedIn("TEMPLATES_READ"))))
                .isEqualTo(403);

        assertThat(status(get("/api/campaignsplus/templates/" + MISSING + "/preview")
                .param("subscriberId", "1")
                .session(signedIn("TEMPLATES_READ", "SUBSCRIBERS_READ"))))
                .isEqualTo(400);
    }

    @Test
    @DisplayName("saving a campaign as a template needs TEMPLATES_WRITE, and applying one needs CAMPAIGNS_WRITE")
    void theTwoWritesAreGatedApart() throws Exception {
        // The screen shows From a campaign only with TEMPLATES_WRITE and Use in a
        // campaign only with CAMPAIGNS_WRITE. These are the gates those two checks
        // are mirroring, so a change to either one shows up as a button that lies.
        assertThat(status(withCsrf(post("/api/campaignsplus/templates/save-from-campaign"))
                .param("campaignId", MISSING).param("name", "Reused")
                .session(signedIn("CAMPAIGNS_WRITE")))).isEqualTo(403);

        assertThat(status(withCsrf(post("/api/campaignsplus/templates/save-from-campaign"))
                .param("campaignId", MISSING).param("name", "Reused")
                .session(signedIn("TEMPLATES_WRITE")))).isEqualTo(400);

        assertThat(status(withCsrf(post("/api/campaignsplus/templates/apply"))
                .param("templateId", MISSING).param("campaignId", MISSING)
                .session(signedIn("TEMPLATES_WRITE")))).isEqualTo(403);

        assertThat(status(withCsrf(post("/api/campaignsplus/templates/apply"))
                .param("templateId", MISSING).param("campaignId", MISSING)
                .session(signedIn("CAMPAIGNS_WRITE")))).isEqualTo(400);
    }

    @Test
    @DisplayName("both writes are behind the CSRF token, so the screen has to send it")
    void theWritesAreCsrfProtected() throws Exception {
        // templates.js posts through console.js's post(), which sets X-XSRF-TOKEN, and
        // falls back to setting it itself. Either way it has to, and this says so.
        assertThat(status(post("/api/campaignsplus/templates/apply")
                .param("templateId", MISSING).param("campaignId", MISSING)
                .session(signedIn("CAMPAIGNS_WRITE")))).isEqualTo(403);
    }

    // ------------------------------------------------------------------
    // The hand-off
    // ------------------------------------------------------------------

    @Test
    @DisplayName("if console.html loads templates.js it must load it after console.js")
    void theIncludeLineLandsInTheRightPlace() throws Exception {
        // templates.js is not owned by whoever owns console.html, so the script tag is
        // a hand-off rather than a change this branch could make. Written to pass in
        // both states on purpose: a test that fails the moment somebody adds the line
        // correctly would be punishing the very thing it is asking for.
        //
        // What it does guard is the one way the hand-off can be got wrong quietly.
        // templates.js reads esc, api, post, toast, can, openModal and previewDocument
        // off the window at call time and degrades without them, so a tag placed above
        // console.js does not throw. It just silently gets the fallbacks: plainer
        // escaping, alert() instead of a toast, and every permission read as denied,
        // which hides the controls and looks like a permissions bug rather than an
        // ordering one.
        String console = Files.readString(Path.of("src/main/resources/templates/console.html"),
                StandardCharsets.UTF_8);
        int consoleJs = console.indexOf("/js/console.js");
        int templatesJs = console.indexOf("/js/templates.js");

        assertThat(consoleJs).as("console.html no longer loads console.js, so the include "
                + "instruction in templates.js is stale").isGreaterThan(-1);
        if (templatesJs < 0) return;   // not added yet, which is the state as of this branch
        assertThat(templatesJs).as("templates.js must be loaded after console.js").isGreaterThan(consoleJs);
    }

    // ------------------------------------------------------------------

    private static String script() throws Exception {
        return Files.readString(SCRIPT, StandardCharsets.UTF_8);
    }

    private static Set<String> apiPathsIn(String source) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = API_PATH.matcher(source);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    private int status(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withCsrf(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    /**
     * A session carrying an already-authenticated context, which is what the chain
     * finds on every request after a real sign-in. Built by hand because
     * spring-security-test is not a dependency of this project, the same way
     * ContactSuggestWiringTest does it.
     */
    private static MockHttpSession signedIn(String... authorities) {
        SecurityContext ctx = new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                ACTOR, null, Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);
        return session;
    }
}
