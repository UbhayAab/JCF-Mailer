package com.jarurat.mailer.push;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What lane a failed send earns, and who decided it.
 *
 * The failure notification is the highest ranked thing this application can raise on
 * its own and it was also the loudest: it was built as lane A in code, so a scheduled
 * message that failed at 03:10 rang a phone at 03:10, and decideSendFailure - which
 * exists precisely to answer this - was never called. The tests below are on the
 * finished payload rather than on the decision, because the payload is the only thing
 * a phone ever sees: lane, the silent flag the service worker reads, and the urgency
 * header that decides whether a push service wakes a sleeping device.
 *
 * No Spring context and no database. The entity manager is stubbed to hand back one
 * rules row, which is the only thing PushService asks it for, and everything else on
 * the fan-out path is real. A context here would prove nothing extra about the lane
 * and would put a live transaction next to a virtual thread pool that writes rows of
 * its own.
 */
class SendFailureLaneTest {

    private static final String USER = "priya@jarurat.care";

    private static final String P256DH =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    private final ObjectMapper json = new ObjectMapper();

    private final PushSubscriptionRepository subscriptions = mock(PushSubscriptionRepository.class);
    private final WebPushSender sender = mock(WebPushSender.class);
    private final PushHealth health = mock(PushHealth.class);
    private final VapidKeys keys = mock(VapidKeys.class);
    private final EntityManager em = mock(EntityManager.class);

    private final PushService push = new PushService(subscriptions, sender, health, keys, 86_400);

    SendFailureLaneTest() {
        when(keys.enabled()).thenReturn(true);
        when(subscriptions.findByMailbox(USER)).thenReturn(List.of(
                new PushSubscriptionRecord(USER, "phone", "https://push.example.net/phone", P256DH, AUTH)));
        when(sender.send(any(), any(), anyString(), anyInt()))
                .thenReturn(PushDelivery.of(PushDelivery.Outcome.DELIVERED, 201, "ok"));
        ReflectionTestUtils.setField(push, "em", em);
    }

    /** The rules this mailbox will be found to have. */
    private void rules(NotificationRules rules) {
        when(em.find(eq(NotificationRules.class), eq(USER))).thenReturn(rules);
    }

    @Test
    @DisplayName("a failure inside quiet hours is delivered in full and silently, not at full volume")
    void quietHoursTakeTheSoundOffAFailure() {
        NotificationRules rules = new NotificationRules(USER);
        // A window containing this hour and the next, in a fixed zone, so this cannot
        // fail for a couple of seconds a day while the clock rolls over.
        int hour = Instant.now().atZone(ZoneOffset.UTC).getHour();
        rules.setZoneId("UTC");
        rules.setQuietEnabled(true);
        rules.setQuietHours(hour, (hour + 2) % 24);
        rules(rules);

        JsonNode payload = fire();

        assertThat(payload.path("lane").asString()).isEqualTo("B");
        assertThat(payload.path("silent").asBoolean()).isTrue();
        // Still sticky, and still renotified. Quiet hours take the sound off a
        // notification; they do not make the one warning about a message that never
        // went dismissable with a sleeve.
        assertThat(payload.path("requireInteraction").asBoolean()).isTrue();
        assertThat(payload.path("renotify").asBoolean()).isTrue();
        assertThat(urgency()).as("normal, so no push service wakes a sleeping phone for it")
                .isEqualTo("normal");
    }

    @Test
    @DisplayName("the same failure outside quiet hours still interrupts, because nothing else reports it")
    void outsideQuietHoursItStillInterrupts() {
        NotificationRules rules = new NotificationRules(USER);
        int hour = Instant.now().atZone(ZoneOffset.UTC).getHour();
        rules.setZoneId("UTC");
        rules.setQuietEnabled(true);
        rules.setQuietHours((hour + 3) % 24, (hour + 5) % 24);
        rules(rules);

        JsonNode payload = fire();

        assertThat(payload.path("lane").asString()).isEqualTo("A");
        assertThat(payload.path("silent").asBoolean()).isFalse();
        assertThat(urgency()).isEqualTo("high");
    }

    @Test
    @DisplayName("a mailbox that never opened the settings sheet gets the default quiet hours, not silence")
    void aMailboxWithNoRowGetsTheDefaults() {
        // No row: em.find answers null, which is the ordinary state of a mailbox that has
        // never touched the notifications screen. The defaults are what that person would
        // be shown if they opened it, so they are what applies.
        rules(null);

        JsonNode payload = fire();

        assertThat(payload.path("type").asString()).isEqualTo("send-failed");
        assertThat(payload.path("lane").asString())
                .as("21:00 to 08:00 Asia/Kolkata, which is either A or B and never nothing")
                .isIn("A", "B");
    }

    @Test
    @DisplayName("rules that cannot be read at all still produce the warning, loudly and on purpose")
    void anUnreadableRuleRowStillWarns() {
        when(em.find(eq(NotificationRules.class), anyString()))
                .thenThrow(new IllegalStateException("the database is not there"));

        JsonNode payload = fire();

        // The one deliberate interrupt-without-asking in the application. A failed send
        // is rare, this is the only channel that reports it, and no screen brings it up
        // later. It is stated in sendFailed rather than left to fall out of a default.
        assertThat(payload.path("lane").asString()).isEqualTo("A");
        assertThat(payload.path("silent").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("the notification still says which message failed and where to go for it")
    void theWarningStillCarriesItsMessage() {
        rules(null);

        JsonNode payload = fire();

        assertThat(payload.path("title").asString()).isEqualTo("Message not sent");
        assertThat(payload.path("body").asString())
                .contains("To sunita@example.org")
                .contains("Camp list for Thane")
                .contains("relay refused");
        assertThat(payload.path("tag").asString()).isEqualTo("jm-fail:44");
        assertThat(payload.path("data").path("url").asString()).isEqualTo("/mail?outbox=44");
    }

    // ------------------------------------------------------------------ helpers

    /** One failed send, joined, because the fan-out is deliberately never joined in production. */
    private JsonNode fire() {
        push.sendFailed(USER, 44L, "Camp list for Thane", "sunita@example.org",
                "The relay refused it.").join();
        return json.readTree(new String(captured().getValue(), StandardCharsets.UTF_8));
    }

    private ArgumentCaptor<byte[]> captured() {
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(sender).send(any(), payload.capture(), anyString(), anyInt());
        return payload;
    }

    private String urgency() {
        ArgumentCaptor<String> urgency = ArgumentCaptor.forClass(String.class);
        verify(sender).send(any(), any(), urgency.capture(), anyInt());
        return urgency.getValue();
    }
}
