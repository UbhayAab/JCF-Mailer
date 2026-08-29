package com.jarurat.mailer.push;

import com.jarurat.mailer.mail.MailCredentialStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The box with no VAPID keys, which is every box until somebody runs VapidKeygen and
 * edits the environment file, and which is also every developer laptop forever.
 *
 * The property being asserted is boring and is the most important one in this package:
 * the application starts. Push is an addition to a mail client that already works
 * without it, and a missing optional notification key that takes the mailbox down with
 * it would be a far worse failure than no notifications. The test profile sets no keys
 * at all, so this context is the unconfigured one by construction rather than by
 * arrangement.
 *
 * The second property is that it fails loudly rather than quietly. An unconfigured
 * push that silently accepted subscriptions would leave a browser showing
 * notifications as switched on with nothing ever arriving, which is precisely the
 * silent degradation this whole feature was told not to repeat.
 */
@SpringBootTest
class PushDisabledBootTest {

    @Autowired VapidKeys keys;
    @Autowired PushService push;
    @Autowired PushApi api;
    @Autowired PushSubscriptionRepository subscriptions;
    @Autowired MailCredentialStore credentials;

    @MockitoBean WebPushSender sender;

    @Test
    @DisplayName("the application boots with no VAPID keys configured")
    void bootsWithoutKeys() {
        assertThat(keys.enabled()).isFalse();
        assertThat(keys.applicationServerKey()).isNull();
        assertThat(keys.disabledReason())
                .contains("PUSH_VAPID_PUBLIC_KEY")
                .contains("VapidKeygen");
    }

    @Test
    @DisplayName("with no keys, a notification is a no-op and never touches the network")
    void notifyingIsANoOp() {
        assertThat(push.notify("priya@jarurat.care",
                PushNotification.deliver("test", "Someone", "Something", "jm-b:x", Map.of()))
                .join()).isEmpty();
        verify(sender, never()).send(any(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("subscribing is refused with the reason, not accepted and quietly ignored")
    void subscribingSaysWhyRatherThanPretending() {
        String mailbox = "priya@jarurat.care";
        credentials.remember(mailbox, "not-a-real-password");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("jarurat.mail.mailbox", mailbox);

        var answer = api.subscribe(null, session, new PushApi.SubscribeRequest(
                "phone", null, "https://push.example.net/abc",
                Map.of("p256dh", "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
                        "auth", "BTBZMqHH6r4Tts7J_aSIgg")));

        assertThat(answer.getStatusCode().value()).isEqualTo(503);
        assertThat(String.valueOf(answer.getBody())).contains("PUSH_VAPID_PUBLIC_KEY");
        // Nothing was stored, so no browser is left believing it is subscribed.
        assertThat(subscriptions.findByMailbox(mailbox)).isEmpty();

        credentials.forget(mailbox);
    }

    @Test
    @DisplayName("the config endpoint says push is off and why, on the screen a person is on")
    void configReportsTheReason() {
        String mailbox = "priya@jarurat.care";
        credentials.remember(mailbox, "not-a-real-password");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("jarurat.mail.mailbox", mailbox);

        Map<String, Object> config = api.config(null, session, "phone", null);

        assertThat(config.get("supported")).isEqualTo(false);
        assertThat(String.valueOf(config.get("reason"))).contains("VapidKeygen");
        assertThat(config.get("state")).isEqualTo("off");

        credentials.forget(mailbox);
    }

    @Test
    @DisplayName("no mailbox open means the same answer every other mail endpoint gives")
    void refusesWithoutAnOpenMailbox() {
        MockHttpSession session = new MockHttpSession();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api.config(null, session, "phone", null))
                .isInstanceOf(com.jarurat.mailer.webmail.MailboxAccess.MailboxLockedException.class);
    }
}
