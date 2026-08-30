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
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The exact JSON the devices sheet reads, key by key, on both paths and both verbs.
 *
 * This test exists because the shape broke by drifting rather than by anybody making a
 * mistake in a single place. mail.js was written against a contract that had not
 * landed, DeviceApi was written before there was a screen, and the two descriptions of
 * the same answer sat in the repository disagreeing for as long as nobody read them
 * side by side. The result was silent in the worst way: data.devices read off a bare
 * JSON array is undefined rather than an error, so the sheet reported that no device
 * was signed in, to everybody, and every revoke answered 404. Nothing failed. Nothing
 * logged. The only symptom was a lost phone that could not be signed out.
 *
 * So the assertions here are deliberately literal and deliberately strict. The row is
 * pinned with containsOnlyKeys rather than by checking that a few fields are present,
 * because a wire shape is broken as much by a key that quietly disappeared as by one
 * that was never added, and the next person to change this answer should have to come
 * here and say what they changed rather than discover it from a support call.
 *
 * The chain is the assembled one, as in DeviceChainWiringTest, because the paths, the
 * CSRF token and the mail-only authorization rules are all part of the contract being
 * pinned and none of them exist in a controller unit test. The mail server is pointed
 * at a closed port and MailboxAccess is replaced, since opening a mailbox for real
 * needs Stalwart; with it replaced, DeviceApi falls back to the signed-in name, which
 * is the same address the token carries.
 */
@SpringBootTest(properties = "jarurat.mail.jmap-url=http://127.0.0.1:1/jmap/")
class DeviceApiWireShapeTest {

    private static final String MAILBOX = "priya@jarurat.care";
    private static final String PASSWORD = "the-mailbox-password";

    /**
     * Every key mail.js reads, and every key this package answered with before the
     * screen existed. Both sets are served, so both are pinned: dropping either half
     * breaks somebody, and this list is the only written record of which half is
     * whose.
     */
    private static final String[] ROW_KEYS = {
            // read by mail.js: deviceRow, deviceSprite and lastUsed
            "id", "name", "platform", "current", "createdAt", "lastSeenAt", "ip", "mailbox",
            // the older names for the same values, kept for anything holding a curl
            "label", "firstSeen", "lastSeen", "lastIp"
    };

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private DeviceTokenRepository repository;

    @MockitoBean
    private MailboxAccess mailboxes;

    private final ObjectMapper json = new ObjectMapper();

    private MockMvc mvc;
    private Cookie csrf;

    @BeforeEach
    void wireTheRealChain() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters((Filter) springSecurityFilterChain)
                .build();
        repository.deleteAll();
        // The same token the browser sends back as a header, taken from the page that
        // sets it, because these endpoints are writes behind the CSRF filter and a
        // hand-made value would only prove that the filter was off.
        csrf = mvc.perform(get("/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
    }

    @Test
    @DisplayName("the list is an object with an enabled flag, not the bare array that read as no devices")
    void theListIsAnObjectTheScreenCanRead() throws Exception {
        Cookie cookie = enrol("family-1", "iPhone Safari", "203.0.113.9");

        Map<String, Object> body = bodyOf(mvc.perform(get("/api/mail/devices").cookie(cookie)).andReturn());

        // The wrapper itself. A bare array here is the whole original defect: the
        // client asks for data.devices and gets undefined, which is falsy, which is an
        // empty list, which is a screen saying nothing is signed in.
        assertThat(body).containsOnlyKeys("enabled", "devices");
        assertThat(body.get("enabled")).isEqualTo(true);

        List<Map<String, Object>> devices = devicesIn(body);
        assertThat(devices).hasSize(1);

        Map<String, Object> row = devices.get(0);
        assertThat(row).containsOnlyKeys(ROW_KEYS);
        assertThat(row.get("id")).isEqualTo("family-1");
        // name is what titles the row and label is what this package always called it.
        assertThat(row.get("name")).isEqualTo("iPhone Safari");
        assertThat(row.get("label")).isEqualTo("iPhone Safari");
        // Present and null rather than absent, so that a reader of this JSON can see
        // that the server has nothing to say here rather than guess it forgot.
        assertThat(row.get("platform")).isNull();
        // The address the request came from, not the one seeded on the row, because
        // presenting the cookie rotates the token and rotation writes the address it
        // was just used from. That is what makes the line under the device name mean
        // "last used from here" rather than "enrolled from here", and it is the half a
        // person uses to decide whether a row is theirs.
        assertThat(row.get("ip")).isEqualTo("127.0.0.1");
        assertThat(row.get("lastIp")).isEqualTo(row.get("ip"));
        assertThat(row.get("mailbox")).isEqualTo(true);
        // The cookie was presented, so this is the device asking.
        assertThat(row.get("current")).isEqualTo(true);
        // ISO-8601 strings, because lastUsed() in mail.js hands them straight to Date
        // and an epoch number would come back as an unknown time.
        assertThat(Instant.parse((String) row.get("lastSeenAt"))).isNotNull();
        assertThat(Instant.parse((String) row.get("createdAt"))).isNotNull();
        assertThat(row.get("lastSeen")).isEqualTo(row.get("lastSeenAt"));
        assertThat(row.get("firstSeen")).isEqualTo(row.get("createdAt"));
    }

    @Test
    @DisplayName("both prefixes answer, because the phone asks the mail one first")
    void theMailPrefixAndTheConsolePrefixAgree() throws Exception {
        Cookie cookie = enrol("family-1", "Android phone Chrome", "203.0.113.9");

        MvcResult first = mvc.perform(get("/api/mail/devices").cookie(cookie)).andReturn();
        // Presenting the cookie spends it, so the second request carries the successor
        // exactly as a browser would; asking twice with the same one would be testing
        // the grace window instead of the two paths.
        MvcResult second = mvc.perform(get("/api/devices").cookie(rotated(first))).andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> mailRow = devicesIn(bodyOf(first)).get(0);
        Map<String, Object> consoleRow = devicesIn(bodyOf(second)).get(0);
        assertThat(consoleRow).containsOnlyKeys(ROW_KEYS);
        assertThat(consoleRow.get("id")).isEqualTo(mailRow.get("id"));
        // Both must mark it as this device. A path that resolved the cookie differently
        // would put the Sign out button on the wrong row.
        assertThat(mailRow.get("current")).isEqualTo(true);
        assertThat(consoleRow.get("current")).isEqualTo(true);
    }

    @Test
    @DisplayName("a form post signs another device out and says it was not this one")
    void postRevokeEndsOneOtherDevice() throws Exception {
        Cookie cookie = enrol("family-1", "iPhone Safari", "203.0.113.9");
        enrol("family-2", "Windows Chrome", "198.51.100.4");

        Map<String, Object> body = bodyOf(revoke("/api/mail/devices/revoke", cookie, "family-2"));

        assertThat(body).containsOnlyKeys("ok", "revoked", "self");
        assertThat(body.get("ok")).isEqualTo(true);
        // self is what sends the browser to the login page. Saying true here would sign
        // somebody out of the session they were using to sign a different phone out.
        assertThat(body.get("self")).isEqualTo(false);

        assertThat(repository.findByFamilyId("family-2")).isEmpty();
        assertThat(repository.findByFamilyId("family-1")).isNotEmpty();
    }

    @Test
    @DisplayName("signing this device out really ends the session, which is what the screen promises")
    void postRevokeOnThisDeviceSignsTheSessionOut() throws Exception {
        Cookie cookie = enrol("family-1", "iPhone Safari", "203.0.113.9");

        MvcResult result = revoke("/api/mail/devices/revoke", cookie, "family-1");
        Map<String, Object> body = bodyOf(result);

        assertThat(body.get("ok")).isEqualTo(true);
        assertThat(body.get("self")).isEqualTo(true);
        assertThat(repository.findByFamilyId("family-1")).isEmpty();

        // The last word on this cookie has to be the one that removes it. The filter
        // rotates the token on the way in and writes a fresh cookie, so a response that
        // did not clear it afterwards would leave the phone holding a credential for a
        // family that no longer exists and paying for a lookup on every request.
        assertThat(lastDeviceCookie(result).getMaxAge()).isZero();

        // And the point of the whole screen: the cookie cannot sign back in.
        assertThat(mvc.perform(get("/api/mail/devices").cookie(cookie)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("sign out the other devices keeps this one, which is what its own button says")
    void postRevokeAllKeepsTheDeviceAsking() throws Exception {
        Cookie cookie = enrol("family-1", "iPhone Safari", "203.0.113.9");
        enrol("family-2", "Windows Chrome", "198.51.100.4");
        enrol("family-3", "Mac Safari", "198.51.100.5");

        MvcResult result = mvc.perform(post("/api/mail/devices/revoke-all")
                .cookie(cookie)
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())).andReturn();
        Map<String, Object> body = bodyOf(result);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body).containsOnlyKeys("ok", "signedOut", "self");
        // The count the toast prints. A wrong number here is a person told two phones
        // were signed out when one was.
        assertThat(body.get("signedOut")).isEqualTo(2);
        assertThat(body.get("self")).isEqualTo(false);

        assertThat(repository.findByFamilyId("family-2")).isEmpty();
        assertThat(repository.findByFamilyId("family-3")).isEmpty();
        // The button above this call counts the rows that are not current and promises
        // in so many words that this device stays signed in. It has to still be here.
        assertThat(repository.findByFamilyId("family-1")).isNotEmpty();
    }

    @Test
    @DisplayName("an id that revokes nothing answers 409, because 404 would read as the wrong path")
    void revokingSomethingAlreadyGoneIsNotA404() throws Exception {
        Cookie cookie = enrol("family-1", "iPhone Safari", "203.0.113.9");

        MvcResult result = revoke("/api/mail/devices/revoke", cookie, "a-family-that-is-not-there");

        // This is the assertion that has to survive. deviceCall in mail.js treats 404
        // and 403 as "this is not the path", tries the other one, and then tells the
        // person that signed-in devices are not available on this server at all. A 404
        // for a stale row would therefore hide the whole feature rather than report one
        // gone device, which is a much larger lie than the one being reported.
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(bodyOf(result)).containsOnlyKeys("error");
        assertThat(bodyOf(result).get("error")).isEqualTo("That device is not signed in any more.");
        // And nothing else was touched on the way past.
        assertThat(repository.findByFamilyId("family-1")).isNotEmpty();
    }

    @Test
    @DisplayName("the DELETE verb is the same call, so the two shapes cannot drift apart again")
    void deleteAnswersExactlyWhatThePostAnswers() throws Exception {
        Cookie cookie = enrol("family-1", "iPhone Safari", "203.0.113.9");
        enrol("family-2", "Windows Chrome", "198.51.100.4");

        MvcResult result = mvc.perform(delete("/api/devices/family-2")
                .cookie(cookie)
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(bodyOf(result)).containsOnlyKeys("ok", "revoked", "self");
        assertThat(bodyOf(result).get("self")).isEqualTo(false);
        assertThat(repository.findByFamilyId("family-2")).isEmpty();
    }

    /**
     * Writes a live token for one device and hands back the cookie a browser holding it
     * would send. Rows rather than a real sign in, because enrolling for real needs
     * Stalwart to accept a password and what is under test here is the answer, not the
     * enrolment.
     */
    private Cookie enrol(String family, String label, String ip) {
        String selector = DeviceCookie.mintSelector();
        String secret = DeviceCookie.mintSecret();
        Instant now = Instant.now();
        repository.save(new DeviceToken(selector, ApiKeyHasher.sha256(secret), family, MAILBOX,
                DeviceCredentialCipher.seal(secret, MAILBOX, PASSWORD), label,
                now, now, ip, now.plus(Duration.ofDays(180))));
        return new Cookie(DeviceCookie.NAME, DeviceCookie.valueOf(selector, secret));
    }

    private MvcResult revoke(String path, Cookie device, String id) throws Exception {
        return mvc.perform(post(path)
                .param("id", id)
                .cookie(device)
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())).andReturn();
    }

    /** The successor the filter minted, which is what the browser would send next. */
    private static Cookie rotated(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(DeviceCookie.NAME);
        assertThat(cookie).as("the filter did not rotate the device cookie").isNotNull();
        return new Cookie(DeviceCookie.NAME, cookie.getValue());
    }

    /**
     * The last Set-Cookie for the device, because a response can carry two and a
     * browser applies them in order. Reading the first would report the rotation the
     * filter wrote on the way in and miss the removal the controller wrote on the way
     * out, which is the one that matters here.
     */
    private static Cookie lastDeviceCookie(MvcResult result) {
        List<Cookie> ours = new ArrayList<>();
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (DeviceCookie.NAME.equals(cookie.getName())) ours.add(cookie);
        }
        assertThat(ours).as("no device cookie was written at all").isNotEmpty();
        return ours.get(ours.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> devicesIn(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("devices");
    }
}
