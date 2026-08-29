package com.jarurat.mailer.push;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the application does with each answer a push service can give, and who it is
 * allowed to give that answer about.
 *
 * The sender is the only thing mocked. Everything else is real, including the
 * repository, because the assertions are about rows appearing and disappearing and a
 * mocked repository would only be able to prove that this test and the service agree
 * about which method to call.
 *
 * The pruning rules are the ones worth pinning down hardest. A push service that
 * answers 410 forever and is never pruned is how this table grows without limit, and
 * a rule that prunes too eagerly is worse: one wrong configuration line answering 403
 * would delete every subscription the foundation has, and every person would have to
 * turn notifications back on by hand without ever being told why.
 */
@SpringBootTest(properties = {
        // A throwaway pair, generated for this file. Real ones live in the environment
        // file on the box and are never in the repository.
        "jarurat.push.vapid.public-key=BC_GVoaEryi6JNFeKybkg50EEKhDwndVKlJHWJl5gMSmKGY2GJVw8KJjMVg1Hgyse5l_EowPW29mzIlQLhuLyr4",
        "jarurat.push.vapid.private-key=MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCBJrXTYNbiwd-ZYlGhU06wKFhaQfAWFcqa0jUiJ7magYw",
        "jarurat.push.vapid.subject=mailto:postmaster@jarurat.care"
})
class PushFanOutTest {

    private static final String P256DH =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    private static final String ALICE = "priya@jarurat.care";
    private static final String BOB = "support@jarurat.care";

    @Autowired PushService push;
    @Autowired PushApi api;
    @Autowired com.jarurat.mailer.mail.MailCredentialStore credentials;
    @Autowired PushSubscriptionRepository subscriptions;
    @Autowired VapidKeys keys;

    @MockitoBean WebPushSender sender;

    @BeforeEach
    void clean() {
        subscriptions.deleteAll();
    }

    private PushSubscriptionRecord give(String mailbox, String device) {
        return push.subscribe(mailbox, device, "https://push.example.net/" + device, P256DH, AUTH);
    }

    private static PushNotification anything() {
        return PushNotification.deliver("test", "Someone", "A subject", "jm-b:test", Map.of());
    }

    @Test
    @DisplayName("the key pair configured here is actually usable, or nothing below means anything")
    void pushIsEnabledForTheseTests() {
        assertThat(keys.enabled()).as(String.valueOf(keys.disabledReason())).isTrue();
    }

    @Test
    @DisplayName("410 Gone deletes the row, because it will answer 410 forever otherwise")
    void prunesOnGone() {
        give(ALICE, "phone");
        when(sender.send(any(), any(), anyString(), anyInt()))
                .thenReturn(PushDelivery.of(PushDelivery.Outcome.GONE, 410, "gone"));

        push.notify(ALICE, anything()).join();

        assertThat(subscriptions.findByMailbox(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("404 is the same answer in different words and prunes the same way")
    void prunesOnNotFound() {
        give(ALICE, "laptop");
        when(sender.send(any(), any(), anyString(), anyInt()))
                .thenReturn(PushDelivery.of(PushDelivery.Outcome.GONE, 404, "no such subscription"));

        push.notify(ALICE, anything()).join();

        assertThat(subscriptions.findByMailbox(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("403 keeps the row, because it usually means somebody changed the VAPID keys")
    void doesNotPruneOnRejection() {
        give(ALICE, "phone");
        when(sender.send(any(), any(), anyString(), anyInt()))
                .thenReturn(PushDelivery.of(PushDelivery.Outcome.REJECTED, 403, "BadJwtToken"));

        push.notify(ALICE, anything()).join();

        List<PushSubscriptionRecord> left = subscriptions.findByMailbox(ALICE);
        assertThat(left).hasSize(1);
        assertThat(left.get(0).getLastStatus()).isEqualTo(403);
        assertThat(left.get(0).getFailureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("429 backs off, and the next notification is not sent to that endpoint at all")
    void honoursRateLimiting() {
        give(ALICE, "phone");
        Instant until = Instant.now().plusSeconds(300);
        when(sender.send(any(), any(), anyString(), anyInt()))
                .thenReturn(new PushDelivery(PushDelivery.Outcome.RATE_LIMITED, 429, "slow down", until));

        push.notify(ALICE, anything()).join();

        PushSubscriptionRecord row = subscriptions.findByMailbox(ALICE).get(0);
        // Compared to the millisecond rather than exactly. Both H2 and PostgreSQL store
        // a timestamp to microseconds, so the nanoseconds Instant.now() carries on this
        // JVM do not survive the round trip and never will.
        assertThat(row.getRetryAfter()).isCloseTo(until,
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
        assertThat(row.sendableAt(Instant.now())).isFalse();

        // The second fan-out must not reach the sender at all. Honouring a rate limit
        // by sending anyway is how a short throttle becomes a long one.
        push.notify(ALICE, anything()).join();
        verify(sender, org.mockito.Mockito.times(1)).send(any(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("413 is recorded and dropped, because the same bytes cannot succeed on a retry")
    void recordsTooLarge() {
        give(ALICE, "phone");
        when(sender.send(any(), any(), anyString(), anyInt()))
                .thenReturn(PushDelivery.of(PushDelivery.Outcome.TOO_LARGE, 413, "payload too large"));

        push.notify(ALICE, anything()).join();

        PushSubscriptionRecord row = subscriptions.findByMailbox(ALICE).get(0);
        assertThat(row.getLastStatus()).isEqualTo(413);
        assertThat(row.getRetryAfter()).isNull();
    }

    @Test
    @DisplayName("one dead endpoint does not stop the others being told")
    void oneDeadEndpointDoesNotStopTheRest() {
        give(ALICE, "phone");
        give(ALICE, "laptop");
        when(sender.send(any(), any(), anyString(), anyInt())).thenAnswer(call -> {
            PushSubscriptionRecord row = call.getArgument(0);
            if ("phone".equals(row.getDeviceId())) {
                throw new IllegalStateException("this endpoint blew up");
            }
            return PushDelivery.of(PushDelivery.Outcome.DELIVERED, 201, null);
        });

        List<PushDelivery> results = push.notify(ALICE, anything()).join();

        assertThat(results).hasSize(2);
        assertThat(results).anyMatch(PushDelivery::delivered);
        // Both rows survive: a task that threw is a failure to record, not evidence
        // that the subscription is gone.
        assertThat(subscriptions.findByMailbox(ALICE)).hasSize(2);
    }

    @Test
    @DisplayName("a notification for one mailbox never reaches another mailbox's devices")
    void mailboxesAreIsolated() {
        give(ALICE, "shared-device-id");
        give(BOB, "shared-device-id");   // the same phone, both mailboxes open on it
        when(sender.send(any(), any(), anyString(), anyInt()))
                .thenReturn(PushDelivery.of(PushDelivery.Outcome.DELIVERED, 201, null));

        push.notify(ALICE, anything()).join();

        // A device id is chosen by the browser, so it is never a key on its own. Two
        // mailboxes on one phone are two rows, and each is told only about its own.
        verify(sender).send(org.mockito.ArgumentMatchers.argThat(
                row -> ALICE.equals(row.getMailbox())), any(), anyString(), anyInt());
        verify(sender, never()).send(org.mockito.ArgumentMatchers.argThat(
                row -> BOB.equals(row.getMailbox())), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("a device belonging to one mailbox cannot be marked seen through another")
    void seenIsScopedToTheMailbox() {
        give(ALICE, "phone");

        assertThat(push.markSeen(BOB, "phone")).isFalse();
        assertThat(push.markSeen(ALICE, "phone")).isTrue();
        assertThat(subscriptions.findByMailboxAndDeviceId(ALICE, "phone").orElseThrow().isPushSeen())
                .isTrue();
    }

    @Test
    @DisplayName("unsubscribing one mailbox leaves the other mailbox on the same phone alone")
    void unsubscribeIsScopedToTheMailbox() {
        give(ALICE, "phone");
        give(BOB, "phone");

        push.unsubscribe(ALICE, "phone");

        assertThat(subscriptions.findByMailbox(ALICE)).isEmpty();
        assertThat(subscriptions.findByMailbox(BOB)).hasSize(1);
    }

    @Test
    @DisplayName("the same device subscribing again is repointed rather than duplicated")
    void repointsRatherThanDuplicating() {
        give(ALICE, "phone");
        push.subscribe(ALICE, "phone", "https://push.example.net/moved", P256DH, AUTH);

        List<PushSubscriptionRecord> rows = subscriptions.findByMailbox(ALICE);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEndpoint()).endsWith("/moved");
        // A repointed row has proved nothing about the new endpoint yet.
        assertThat(rows.get(0).isPushSeen()).isFalse();
    }

    @Test
    @DisplayName("keys that could never be encrypted for are refused at the door")
    void refusesUnusableSubscriptions() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> push.subscribe(ALICE, "phone", "http://push.example.net/x", P256DH, AUTH))
                .hasMessageContaining("https");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> push.subscribe(ALICE, "phone", "https://push.example.net/x", P256DH, "c2hvcnQ"))
                .hasMessageContaining("16 bytes");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> push.subscribe(ALICE, "phone", "https://push.example.net/x", AUTH, AUTH))
                .hasMessageContaining("65 byte");
        assertThat(subscriptions.findByMailbox(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("the field names the browser actually sends are the field names accepted")
    void speaksTheSameLanguageAsTheBrowser() {
        // notify.js and sw.js both send deviceClientId, which is what RFC 8620 calls
        // this field. A server that only understood deviceId would answer 200 to every
        // request, store a row under an empty device id, and notifications would simply
        // never switch on with nothing failing anywhere. This test is here because that
        // mismatch existed and was found by reading the other half rather than by any
        // of the tests above.
        credentials.remember(ALICE, "not-a-real-password");
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("jarurat.mail.mailbox", ALICE);

        var answer = api.subscribe(null, session, new PushApi.SubscribeRequest(
                "phone-from-the-browser", null, "https://push.example.net/abc",
                Map.of("p256dh", P256DH, "auth", AUTH)));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(subscriptions.findByMailboxAndDeviceId(ALICE, "phone-from-the-browser"))
                .isPresent();

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) api.config(
                null, session, "phone-from-the-browser", null);
        // The names the transport research specified and the page reads back.
        assertThat(config).containsKeys("supported", "applicationServerKey", "state",
                "emailPush", "pushSeen", "expiresAt");
        assertThat(config.get("state")).isEqualTo("pending");   // no push has landed yet

        credentials.forget(ALICE);
    }

    @Test
    @DisplayName("Retry-After is read in both of the shapes it comes in")
    void readsRetryAfterInBothShapes() {
        Instant now = Instant.parse("2026-08-29T10:00:00Z");

        assertThat(WebPushSender.parseRetryAfter("120", now))
                .isEqualTo(now.plusSeconds(120));
        // The HTTP date form. Reading only the numeric one leaves this parsed as zero,
        // which is a rate limit honoured by ignoring it.
        assertThat(WebPushSender.parseRetryAfter("Sat, 29 Aug 2026 10:05:00 GMT", now))
                .isEqualTo(Instant.parse("2026-08-29T10:05:00Z"));
        assertThat(WebPushSender.parseRetryAfter(null, now)).isEqualTo(now.plusSeconds(60));
        assertThat(WebPushSender.parseRetryAfter("not a date at all", now))
                .isEqualTo(now.plusSeconds(60));
    }
}
