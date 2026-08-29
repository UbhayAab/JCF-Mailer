package com.jarurat.mailer.push;

import com.jarurat.mailer.mail.InMemoryMailCredentialStore;
import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The JSON this application would actually put on the wire to Stalwart, and the two
 * mistakes that are easiest to make and hardest to notice.
 *
 * The first is passing an accountId. Every other JMAP method in this application takes
 * one, JmapClient has a helper that fills it in, and PushSubscription is the one method
 * pair in RFC 8620 that has no accountId at all: sending one is an invalidArguments
 * error and the registration silently does not happen.
 *
 * The second is skipping the destroy. Stalwart does not deduplicate by deviceClientId,
 * so subscribing twice from the same phone leaves two live subscriptions and every
 * message arrives twice. That looks exactly like a bug in the service worker, which is
 * where somebody would then spend an afternoon.
 *
 * The client here is a mock whose four small JSON helpers are the real ones, so what is
 * asserted on is the request the application genuinely builds rather than a restatement
 * of its own source.
 */
class JmapPushRegistrarTest {

    private static final String MAILBOX = "priya@jarurat.care";
    private static final String P256DH =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    private final ObjectMapper json = new ObjectMapper();
    private final List<ArrayNode> requests = new ArrayList<>();
    private final List<List<String>> capabilities = new ArrayList<>();

    @Test
    @DisplayName("registers, destroys the stale subscription first, and completes the handshake")
    void registersAndVerifies() {
        JmapClient client = fakeClient();
        PushSubscriptionRepository repository = mock(PushSubscriptionRepository.class);
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        InMemoryMailCredentialStore credentials = new InMemoryMailCredentialStore();
        credentials.remember(MAILBOX, "the-password");

        JmapPushRegistrar registrar =
                new JmapPushRegistrar(client, credentials, repository, new PushHealth());
        PushSubscriptionRecord row = new PushSubscriptionRecord(
                MAILBOX, "phone", "https://web.push.apple.com/QK9", P256DH, AUTH);

        assertThat(registrar.register(MAILBOX, row)).isTrue();

        assertThat(row.getJmapSubscriptionId()).isEqualTo("sub-new");
        assertThat(row.isJmapVerified()).isTrue();
        // Stalwart hard clamps this to seven days, so asking for exactly seven is the
        // only request that gets what it asked for.
        assertThat(row.getJmapExpiresAt())
                .isAfter(Instant.now().plusSeconds(6 * 86_400))
                .isBefore(Instant.now().plusSeconds(8 * 86_400));

        // The stale subscription for this same device is destroyed before anything is
        // created, or the phone shows every message twice.
        assertThat(methodsSent()).containsSubsequence(
                "PushSubscription/get", "PushSubscription/set", "PushSubscription/set");
        assertThat(argsFor("PushSubscription/set", "d0").path("destroy").get(0).asString())
                .isEqualTo("sub-stale");

        ObjectNode created = (ObjectNode) argsFor("PushSubscription/set", "c0")
                .path("create").path("c0");
        assertThat(created.path("url").asString()).isEqualTo("https://web.push.apple.com/QK9");
        assertThat(created.path("keys").path("p256dh").asString()).isEqualTo(P256DH);
        assertThat(created.path("keys").path("auth").asString()).isEqualTo(AUTH);
        assertThat(created.path("types").get(0).asString()).isEqualTo("EmailDelivery");

        // No accountId, on the method arguments or anywhere inside them. This is the
        // one JMAP method pair that has none.
        assertThat(argsFor("PushSubscription/set", "c0").has("accountId")).isFalse();
        assertThat(argsFor("PushSubscription/get", "g0").has("accountId")).isFalse();

        // The code read back from /get is echoed to /set, which is the handshake done
        // without a browser round trip. It proves the subscription exists and proves
        // nothing whatever about whether a push can reach the device.
        assertThat(argsFor("PushSubscription/set", "v1")
                .path("update").path("sub-new").path("verificationCode").asString())
                .isEqualTo("THECODE");
        assertThat(row.isPushSeen()).isFalse();

        // emailPush is a Stalwart extension that puts the message itself inside the
        // encrypted payload, so the service worker can name a sender and a subject
        // with this server switched off entirely. It has to be declared in using or
        // the property is rejected, and the create is retried without it if the
        // server does not know it.
        assertThat(created.has("emailPush")).isTrue();
        assertThat(capabilities).anyMatch(u -> u.contains("urn:ietf:params:jmap:emailpush"));
    }

    @Test
    @DisplayName("a mailbox nobody has open is not registered, because there is no credential to do it with")
    void needsTheMailboxCredential() {
        JmapClient client = fakeClient();
        JmapPushRegistrar registrar = new JmapPushRegistrar(client, new InMemoryMailCredentialStore(),
                mock(PushSubscriptionRepository.class), new PushHealth());

        PushSubscriptionRecord row = new PushSubscriptionRecord(
                MAILBOX, "phone", "https://web.push.apple.com/QK9", P256DH, AUTH);

        assertThat(registrar.register(MAILBOX, row)).isFalse();
        assertThat(requests).isEmpty();
    }

    // ------------------------------------------------------------------

    private List<String> methodsSent() {
        List<String> names = new ArrayList<>();
        for (ArrayNode request : requests) {
            for (JsonNode call : request) names.add(call.get(0).asString());
        }
        return names;
    }

    private ObjectNode argsFor(String method, String callId) {
        for (ArrayNode request : requests) {
            for (JsonNode call : request) {
                if (method.equals(call.get(0).asString()) && callId.equals(call.get(2).asString())) {
                    return (ObjectNode) call.get(1);
                }
            }
        }
        throw new AssertionError("No " + method + " was sent with call id " + callId
                + ". Sent: " + methodsSent());
    }

    /**
     * The JSON builders and the response reader are the real ones; only the socket is
     * stubbed. A fake that also stubbed the builders could only prove that this test
     * and the registrar agree with each other.
     */
    private JmapClient fakeClient() {
        JmapClient client = mock(JmapClient.class);
        when(client.newObject()).thenAnswer(i -> json.createObjectNode());
        when(client.newArray()).thenAnswer(i -> json.createArrayNode());
        when(client.session(anyString())).thenReturn(new MailSession(
                MAILBOX, "acc-1", URI.create("https://localhost/jmap/"), "", "", "state"));
        when(client.invocation(anyString(), any(), anyString())).thenAnswer(i -> {
            ArrayNode call = json.createArrayNode();
            call.add(i.getArgument(0, String.class));
            call.add((JsonNode) i.getArgument(1));
            call.add(i.getArgument(2, String.class));
            return call;
        });
        when(client.call(anyString(), anyList(), any())).thenAnswer(i -> {
            capabilities.add(i.getArgument(1));
            ArrayNode methodCalls = i.getArgument(2);
            requests.add(methodCalls);
            return answer(methodCalls);
        });
        when(client.response(any(), anyString(), anyString())).thenAnswer(i -> {
            JsonNode responses = i.getArgument(0);
            for (JsonNode entry : responses) {
                if (entry.get(0).asString().equals(i.getArgument(1))
                        && entry.get(2).asString().equals(i.getArgument(2))) {
                    return entry.get(1);
                }
            }
            throw new AssertionError("no response for " + i.getArgument(1));
        });
        return client;
    }

    private ArrayNode answer(ArrayNode methodCalls) {
        ArrayNode responses = json.createArrayNode();
        for (JsonNode call : methodCalls) {
            String method = call.get(0).asString();
            String id = call.get(2).asString();
            ObjectNode result = json.createObjectNode();

            if ("PushSubscription/get".equals(method) && "g0".equals(id)) {
                ObjectNode stale = json.createObjectNode();
                stale.put("id", "sub-stale");
                stale.put("deviceClientId", "phone");
                result.putArray("list").add(stale);
            } else if ("PushSubscription/set".equals(method) && "c0".equals(id)) {
                result.putObject("created").putObject("c0").put("id", "sub-new");
            } else if ("PushSubscription/get".equals(method) && "v0".equals(id)) {
                ObjectNode fresh = json.createObjectNode();
                fresh.put("id", "sub-new");
                // Stalwart returns the code from /get, which RFC 8620 does not forbid.
                fresh.put("verificationCode", "THECODE");
                result.putArray("list").add(fresh);
            } else if ("PushSubscription/set".equals(method) && "v1".equals(id)) {
                result.putObject("updated").putNull("sub-new");
            }

            ArrayNode entry = json.createArrayNode();
            entry.add(method);
            entry.add(result);
            entry.add(id);
            responses.add(entry);
        }
        return responses;
    }
}
