package com.jarurat.mailer.device;

import com.jarurat.mailer.repositories.AuditLogRepository;
import com.jarurat.mailer.security.ApiKeyHasher;
import com.jarurat.mailer.services.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The token lifecycle against a real database, because every interesting thing here
 * is a question about rows: which one is live, which one has been spent, and what a
 * second presentation of a spent one does.
 *
 * The replay test is the one to keep. Rotation on its own is easy and looks finished,
 * and a remember-me that rotates without detecting reuse is the shape almost every
 * implementation of this ends up with: a stolen cookie still works exactly once, and
 * nothing anywhere ever notices that it was stolen.
 */
@DataJpaTest
class DeviceTokenServiceTest {

    private static final String MAILBOX = "priya@jarurat.care";
    private static final String OTHER_MAILBOX = "hr@jarurat.care";
    private static final String PASSWORD = "the-mailbox-password";
    private static final String IP = "203.0.113.9";
    private static final String LABEL = "iPhone Safari";

    @Autowired
    private DeviceTokenRepository repository;

    @Autowired
    private AuditLogRepository auditLogs;

    private DeviceTokenService tokens;

    @BeforeEach
    void buildTheService() {
        tokens = service(new DeviceSettings(true, true, 180, 60, 12));
    }

    private DeviceTokenService service(DeviceSettings settings) {
        return new DeviceTokenService(repository, settings, new AuditService(auditLogs));
    }

    @Test
    @DisplayName("every use spends the token and hands back a new one for the same device")
    void theTokenRotatesOnUse() {
        DeviceCookie.Presented first = enrol();
        DeviceToken firstRow = repository.findBySelector(first.selector()).orElseThrow();

        MockHttpServletResponse response = new MockHttpServletResponse();
        Optional<DeviceTokenService.Restored> restored = tokens.restore(first, IP, response);

        assertThat(restored).isPresent();
        assertThat(restored.get().mailbox()).isEqualTo(MAILBOX);
        assertThat(restored.get().mailboxSecret()).isEqualTo(PASSWORD);

        DeviceCookie.Presented second = cookieOn(response);
        assertThat(second).isNotNull();
        assertThat(second.selector()).isNotEqualTo(first.selector());
        assertThat(second.secret()).isNotEqualTo(first.secret());

        DeviceToken secondRow = repository.findBySelector(second.selector()).orElseThrow();
        // One device, two rows: the family is what a person revokes and it survives.
        assertThat(secondRow.getFamilyId()).isEqualTo(firstRow.getFamilyId());
        assertThat(secondRow.getFirstSeenAt()).isEqualTo(firstRow.getFirstSeenAt());
        assertThat(secondRow.getLastIp()).isEqualTo(IP);
        assertThat(secondRow.getSupersededAt()).isNull();
        assertThat(repository.findBySelector(first.selector()).orElseThrow().getSupersededAt()).isNotNull();

        // The successor carries the credential forward under its own key, so the chain
        // does not depend on the row it replaced still existing.
        assertThat(DeviceCredentialCipher.open(second.secret(), MAILBOX, secondRow.getCredentialEnvelope()))
                .contains(PASSWORD);
        assertThat(DeviceCredentialCipher.open(first.secret(), MAILBOX, secondRow.getCredentialEnvelope()))
                .isEmpty();

        // And the successor works, which is the whole point of handing it over.
        assertThat(tokens.restore(second, IP, new MockHttpServletResponse())).isPresent();
    }

    @Test
    @DisplayName("replaying a token that has already been rotated revokes the whole device")
    void replayRevokesTheFamily() {
        DeviceCookie.Presented stolen = enrol();
        String family = repository.findBySelector(stolen.selector()).orElseThrow().getFamilyId();

        MockHttpServletResponse legitimate = new MockHttpServletResponse();
        DeviceCookie.Presented successor = cookieOn(rotate(stolen, legitimate));

        // The thief presents the copy they took, well after the phone used it.
        ageSupersededRows(Duration.ofMinutes(10));
        MockHttpServletResponse replay = new MockHttpServletResponse();
        assertThat(tokens.restore(stolen, "198.51.100.7", replay)).isEmpty();

        // Not just the replayed token. The successor the thief would have rotated into,
        // and every other row of this device, is gone as well, which is the difference
        // between detecting theft and merely inconveniencing it.
        assertThat(repository.findByFamilyId(family)).isEmpty();
        assertThat(tokens.restore(successor, IP, new MockHttpServletResponse())).isEmpty();
        assertThat(clearedCookie(replay)).isTrue();
        assertThat(auditLogs.findAll())
                .anyMatch(row -> "DEVICE_TOKEN_REPLAY".equals(row.getAction()));
    }

    @Test
    @DisplayName("a phone waking two requests at once is not treated as a thief")
    void theGraceWindowKeepsAnHonestDeviceSignedIn() {
        DeviceCookie.Presented cookie = enrol();
        String family = repository.findBySelector(cookie.selector()).orElseThrow().getFamilyId();

        rotate(cookie, new MockHttpServletResponse());

        // The second request of the same wake-up, carrying the same cookie, arriving
        // while the first one's Set-Cookie is still in flight.
        MockHttpServletResponse sibling = new MockHttpServletResponse();
        assertThat(tokens.restore(cookie, IP, sibling)).isPresent();
        assertThat(cookieOn(sibling)).isNotNull();
        assertThat(repository.findByFamilyId(family)).isNotEmpty();

        // With no window at all the same sequence is a replay, which is exactly what
        // would happen to every phone in the building on the first unlock of the day.
        DeviceTokenService strict = service(new DeviceSettings(true, true, 180, 0, 12));
        DeviceCookie.Presented other = enrol();
        rotate(other, new MockHttpServletResponse());
        ageSupersededRows(Duration.ofSeconds(1));
        assertThat(strict.restore(other, IP, new MockHttpServletResponse())).isEmpty();
    }

    @Test
    @DisplayName("an expired token opens nothing and takes its rows with it")
    void expiryEndsTheDevice() {
        String secret = DeviceCookie.mintSecret();
        Instant longAgo = Instant.now().minus(Duration.ofDays(200));
        DeviceCookie.Presented expired = store(MAILBOX, secret, longAgo, longAgo.plus(Duration.ofDays(180)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(tokens.restore(expired, IP, response)).isEmpty();
        assertThat(repository.findBySelector(expired.selector())).isEmpty();
        assertThat(clearedCookie(response)).isTrue();
    }

    @Test
    @DisplayName("a token is bound to one mailbox and cannot be pointed at another")
    void theTokenIsBoundToItsMailbox() {
        DeviceCookie.Presented mine = enrol();
        DeviceToken row = repository.findBySelector(mine.selector()).orElseThrow();

        assertThat(row.getMailbox()).isEqualTo(MAILBOX);
        assertThat(tokens.restore(mine, IP, new MockHttpServletResponse()).orElseThrow().mailbox())
                .isEqualTo(MAILBOX);

        // The envelope is keyed to the address, so moving the row to another mailbox
        // does not move the credential with it: the column becomes unreadable.
        assertThat(DeviceCredentialCipher.open(mine.secret(), OTHER_MAILBOX, row.getCredentialEnvelope()))
                .isEmpty();

        // And one mailbox cannot revoke another's device, even holding the family id.
        assertThat(tokens.revoke(OTHER_MAILBOX, row.getFamilyId())).isFalse();
        assertThat(repository.findByFamilyId(row.getFamilyId())).isNotEmpty();
        assertThat(tokens.list(OTHER_MAILBOX, mine)).isEmpty();
        assertThat(tokens.revoke(MAILBOX, row.getFamilyId())).isTrue();
    }

    @Test
    @DisplayName("the row a database thief reads holds no password and no working token")
    void theStoredRowIsWorthNothingOnItsOwn() {
        DeviceCookie.Presented cookie = enrol();
        DeviceToken row = repository.findBySelector(cookie.selector()).orElseThrow();

        assertThat(row.getSecretHash()).isNotEqualTo(cookie.secret());
        assertThat(row.getSecretHash()).isEqualTo(ApiKeyHasher.sha256(cookie.secret()));
        assertThat(row.getCredentialEnvelope()).doesNotContain(PASSWORD);

        // Presenting the row's own contents back as a cookie, which is the best a
        // database thief can do without the browser.
        DeviceCookie.Presented forged = new DeviceCookie.Presented(row.getSelector(), row.getSecretHash());
        assertThat(tokens.restore(forged, "198.51.100.7", new MockHttpServletResponse())).isEmpty();
        // And a wrong secret does not revoke the device, or reading a selector would be
        // enough to sign a colleague's phone out.
        assertThat(repository.findByFamilyId(row.getFamilyId())).isNotEmpty();
    }

    @Test
    @DisplayName("signing out revokes the device, and the list shows what is left")
    void listingAndRevoking() {
        DeviceCookie.Presented phone = enrol();
        DeviceCookie.Presented laptop = enrol();

        List<DeviceSummary> devices = tokens.list(MAILBOX, laptop);
        assertThat(devices).hasSize(2);
        assertThat(devices).filteredOn(DeviceSummary::current).hasSize(1);
        assertThat(devices.get(0).label()).isEqualTo(LABEL);
        assertThat(devices.get(0).lastIp()).isEqualTo(IP);

        tokens.revoke(MAILBOX, repository.findBySelector(phone.selector()).orElseThrow().getFamilyId());
        assertThat(tokens.list(MAILBOX, laptop)).hasSize(1);

        assertThat(tokens.revokeAll(MAILBOX)).isEqualTo(1);
        assertThat(tokens.list(MAILBOX, laptop)).isEmpty();
        assertThat(tokens.restore(laptop, IP, new MockHttpServletResponse())).isEmpty();
    }

    @Test
    @DisplayName("a mailbox cannot enrol devices without limit")
    void theNumberOfDevicesIsBounded() {
        DeviceTokenService bounded = service(new DeviceSettings(true, true, 180, 60, 3));
        for (int i = 0; i < 6; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            bounded.enrol(null, MAILBOX, PASSWORD, LABEL, IP, response);
        }
        assertThat(bounded.list(MAILBOX, null)).hasSize(3);
    }

    @Test
    @DisplayName("with the feature switched off nothing is issued and nothing is honoured")
    void theKillSwitchIsComplete() {
        DeviceCookie.Presented live = enrol();
        DeviceTokenService off = service(new DeviceSettings(false, true, 180, 60, 12));

        MockHttpServletResponse response = new MockHttpServletResponse();
        off.enrol(null, MAILBOX, PASSWORD, LABEL, IP, response);
        assertThat(cookieOn(response)).isNull();
        assertThat(off.restore(live, IP, new MockHttpServletResponse())).isEmpty();
    }

    /** Enrols one device and returns the cookie the browser would now hold. */
    private DeviceCookie.Presented enrol() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        tokens.enrol(null, MAILBOX, PASSWORD, LABEL, IP, response);
        DeviceCookie.Presented cookie = cookieOn(response);
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private MockHttpServletResponse rotate(DeviceCookie.Presented cookie, MockHttpServletResponse response) {
        assertThat(tokens.restore(cookie, IP, response)).isPresent();
        return response;
    }

    /** Writes a row directly, which is the only way to test a state time would take months to reach. */
    private DeviceCookie.Presented store(String mailbox, String secret, Instant seen, Instant expires) {
        String selector = DeviceCookie.mintSelector();
        repository.saveAndFlush(new DeviceToken(selector, ApiKeyHasher.sha256(secret),
                "family-" + selector, mailbox,
                DeviceCredentialCipher.seal(secret, mailbox, PASSWORD),
                LABEL, seen, seen, IP, expires));
        return new DeviceCookie.Presented(selector, secret);
    }

    /**
     * Moves every spent row back in time. The grace window is measured against the
     * clock, and a test that slept through it would add a minute to the suite for
     * every case that needs one.
     */
    private void ageSupersededRows(Duration by) {
        for (DeviceToken row : repository.findAll()) {
            if (row.getSupersededAt() != null) {
                row.setSupersededAt(row.getSupersededAt().minus(by));
                repository.saveAndFlush(row);
            }
        }
    }

    private static DeviceCookie.Presented cookieOn(MockHttpServletResponse response) {
        String value = latestCookieValue(response);
        if (value == null || value.isEmpty()) return null;
        String[] parts = value.split("\\.");
        return new DeviceCookie.Presented(parts[1], parts[2]);
    }

    /** A Set-Cookie with an empty value and Max-Age=0 is the browser being told to forget it. */
    private static boolean clearedCookie(MockHttpServletResponse response) {
        return "".equals(latestCookieValue(response));
    }

    private static String latestCookieValue(MockHttpServletResponse response) {
        String value = null;
        for (String header : response.getHeaders("Set-Cookie")) {
            if (!header.startsWith(DeviceCookie.NAME + "=")) continue;
            String rest = header.substring(DeviceCookie.NAME.length() + 1);
            int semicolon = rest.indexOf(';');
            value = semicolon < 0 ? rest : rest.substring(0, semicolon);
        }
        return value;
    }
}
