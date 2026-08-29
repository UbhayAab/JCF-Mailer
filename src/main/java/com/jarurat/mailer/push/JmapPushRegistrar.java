package com.jarurat.mailer.push;

import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailCredentialStore;
import com.jarurat.mailer.mail.MailException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Registers a browser's own push subscription with Stalwart, so that new mail reaches
 * a phone while nothing of ours is running.
 *
 * WHY THIS EXISTS AT ALL WHEN WE ALREADY SEND OUR OWN PUSHES
 * ------------------------------------------------------------------------------
 * The two halves answer different questions and neither can answer the other's. This
 * application knows when a send failed and when the bounce rate crossed a threshold,
 * because those happen inside this process; it does not know that mail arrived, and
 * the only ways for it to find out are to hold a mailbox password in a database or to
 * keep a credentialled connection open per mailbox, both of which the transport
 * research rejected and this file is not going to reintroduce. Stalwart knows the
 * moment mail arrives and will push it itself, encrypted and VAPID signed, straight
 * to the device, with our process not in the path.
 *
 * THE ONE CONFIGURATION LINE THIS DEPENDS ON, WHICH IS NOT OPTIONAL
 * ------------------------------------------------------------------------------
 * A browser gives its push service one application server key when it subscribes and
 * RFC 8292 requires the service to reject anything signed by a different one. There is
 * exactly one subscription per service worker registration, so this application and
 * Stalwart cannot each have their own key and both reach the same device: whichever
 * did not sign the subscription gets a 403 forever. The resolution is that they share
 * one pair. The PEM this application's private key came from also goes into Stalwart's
 * jmap.webPushKey setting, and jmap.webPushContact is set to the same mailto as
 * PUSH_VAPID_SUBJECT. Skip that and everything here appears to succeed, Stalwart posts
 * to Apple, Apple refuses the signature, and nobody ever finds out.
 *
 * The seven day ceiling is Stalwart's and is hard clamped in its source, so a
 * registration made today lapses next week and renewing needs the mailbox credential.
 * PushMaintenance does the renewing; what matters here is that the expiry is recorded
 * on the row so a screen can say when notifications will stop rather than letting them
 * stop quietly.
 */
@Component
public class JmapPushRegistrar {

    /** Stalwart clamps anything longer to this, silently, so ask for exactly it. */
    static final Duration MAX_LIFETIME = Duration.ofDays(7);

    private static final String EMAIL_PUSH = "urn:ietf:params:jmap:emailpush";

    private static final DateTimeFormatter UTC_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final JmapClient jmap;
    private final MailCredentialStore credentials;
    private final PushSubscriptionRepository subscriptions;
    private final PushHealth health;

    public JmapPushRegistrar(JmapClient jmap, MailCredentialStore credentials,
                             PushSubscriptionRepository subscriptions, PushHealth health) {
        this.jmap = jmap;
        this.credentials = credentials;
        this.subscriptions = subscriptions;
        this.health = health;
    }

    /**
     * Registers one device and completes the verification handshake, then records what
     * happened on the row.
     *
     * Answers false rather than throwing when the mail server will not play. Push from
     * this application still works without Stalwart's half, so a mail server that has
     * no web push key configured has to degrade to "no new mail notifications while
     * the browser is closed" and not to "subscribing fails". The reason is recorded in
     * PushHealth, which is on the settings screen, so it degrades visibly.
     */
    public boolean register(String mailbox, PushSubscriptionRecord row) {
        if (!credentials.knows(mailbox)) return false;
        try {
            destroyStale(mailbox, row.getDeviceId());

            String id = create(mailbox, row, true);
            boolean withPreview = id != null;
            if (id == null) id = create(mailbox, row, false);
            if (id == null) return false;

            row.setEmailPush(withPreview);
            row.setJmapSubscriptionId(id);
            row.setJmapExpiresAt(Instant.now().plus(MAX_LIFETIME));
            row.setJmapVerified(verify(mailbox, id));
            subscriptions.save(row);
            return true;
        } catch (MailException e) {
            health.record(mailbox, "JMAP_REGISTER",
                    "The mail server would not register this device for new mail notifications: "
                            + e.getMessage());
            return false;
        }
    }

    /**
     * Pushes the expiry out to another seven days.
     *
     * Extending an expiry does not need the verification handshake again, which RFC
     * 8620 says explicitly, so this is one plain update. It does need the mailbox
     * credential, and that is the whole limitation of the design: notifications keep
     * working for as long as somebody opens this mailbox at least once a week.
     */
    public boolean renew(String mailbox, PushSubscriptionRecord row) {
        if (!credentials.knows(mailbox) || row.getJmapSubscriptionId() == null) return false;
        try {
            Instant expires = Instant.now().plus(MAX_LIFETIME);
            ObjectNode update = jmap.newObject();
            update.putObject("update").putObject(row.getJmapSubscriptionId())
                    .put("expires", UTC_DATE.format(expires));

            ArrayNode calls = jmap.newArray();
            calls.add(jmap.invocation("PushSubscription/set", update, "r0"));
            JsonNode responses = jmap.call(mailbox, List.of(JmapClient.CORE), calls);
            JsonNode result = jmap.response(responses, "PushSubscription/set", "r0");

            if (!result.path("notUpdated").path(row.getJmapSubscriptionId()).isMissingNode()) {
                // The subscription is gone at the far end. Clearing the id is what lets
                // the next subscribe make a fresh one instead of renewing a ghost.
                row.setJmapSubscriptionId(null);
                row.setJmapExpiresAt(null);
                row.setJmapVerified(false);
                subscriptions.save(row);
                return false;
            }
            row.setJmapExpiresAt(expires);
            subscriptions.save(row);
            return true;
        } catch (MailException e) {
            health.record(mailbox, "JMAP_RENEW",
                    "Could not extend new mail notifications for one device: " + e.getMessage());
            return false;
        }
    }

    /** Best effort. A subscription we cannot destroy will expire within a week anyway. */
    public void unregister(String mailbox, PushSubscriptionRecord row) {
        if (row.getJmapSubscriptionId() == null || !credentials.knows(mailbox)) return;
        try {
            ObjectNode args = jmap.newObject();
            args.putArray("destroy").add(row.getJmapSubscriptionId());
            ArrayNode calls = jmap.newArray();
            calls.add(jmap.invocation("PushSubscription/set", args, "d0"));
            jmap.call(mailbox, List.of(JmapClient.CORE), calls);
        } catch (MailException e) {
            health.record(mailbox, "JMAP_UNREGISTER", e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    /**
     * Stalwart does not deduplicate by deviceClientId, so creating twice for the same
     * device leaves two live subscriptions and the phone shows every message twice.
     * That looks exactly like a bug in the service worker and is not one, so the stale
     * ones are destroyed before anything is created.
     */
    private void destroyStale(String mailbox, String deviceId) {
        ObjectNode args = jmap.newObject();
        args.putNull("ids");
        ArrayNode calls = jmap.newArray();
        calls.add(jmap.invocation("PushSubscription/get", args, "g0"));
        JsonNode list = jmap.response(jmap.call(mailbox, List.of(JmapClient.CORE), calls),
                "PushSubscription/get", "g0").path("list");
        if (!list.isArray() || list.isEmpty()) return;

        ArrayNode doomed = jmap.newArray();
        for (JsonNode entry : list) {
            if (deviceId.equals(JmapClient.text(entry, "deviceClientId"))) {
                doomed.add(JmapClient.text(entry, "id"));
            }
        }
        if (doomed.isEmpty()) return;

        ObjectNode destroy = jmap.newObject();
        destroy.set("destroy", doomed);
        ArrayNode kill = jmap.newArray();
        kill.add(jmap.invocation("PushSubscription/set", destroy, "d0"));
        jmap.call(mailbox, List.of(JmapClient.CORE), kill);
    }

    /**
     * PushSubscription takes no accountId, which is why this builds its arguments with
     * newObject rather than with JmapClient.accountArgs. Passing one is an
     * invalidArguments error, and the account is derived from the credential anyway.
     *
     * withEmailPush asks Stalwart to put the message itself inside the payload rather
     * than a bare state change, which is what lets the service worker show a sender and
     * a subject with our server switched off entirely. It is a Stalwart extension, so
     * the caller retries without it once, and a server that does not have it degrades
     * to a notification that says only that something arrived.
     */
    private String create(String mailbox, PushSubscriptionRecord row, boolean withEmailPush) {
        ObjectNode subscription = jmap.newObject();
        subscription.put("deviceClientId", row.getDeviceId());
        subscription.put("url", row.getEndpoint());
        ObjectNode keys = subscription.putObject("keys");
        keys.put("p256dh", row.getUaPublic());
        keys.put("auth", row.getAuthSecret());
        subscription.putArray("types").add("EmailDelivery");
        subscription.put("expires", UTC_DATE.format(Instant.now().plus(MAX_LIFETIME)));

        if (withEmailPush) {
            ObjectNode perAccount = subscription.putObject("emailPush")
                    .putObject(jmap.session(mailbox).accountId());
            ArrayNode properties = perAccount.putArray("properties");
            for (String property : List.of("id", "threadId", "subject", "from", "receivedAt", "preview")) {
                properties.add(property);
            }
            perAccount.put("urgency", "high");
        }

        ObjectNode args = jmap.newObject();
        args.putObject("create").set("c0", subscription);

        ArrayNode calls = jmap.newArray();
        calls.add(jmap.invocation("PushSubscription/set", args, "c0"));

        try {
            JsonNode result = jmap.response(
                    jmap.call(mailbox, withEmailPush ? List.of(JmapClient.CORE, EMAIL_PUSH)
                            : List.of(JmapClient.CORE), calls),
                    "PushSubscription/set", "c0");
            String id = JmapClient.text(result.path("created").path("c0"), "id");
            if (id == null) {
                health.record(mailbox, "JMAP_REGISTER", "The mail server refused the subscription: "
                        + result.path("notCreated").path("c0").toString());
            }
            return id;
        } catch (MailException e) {
            if (withEmailPush) return null;   // the caller retries without the extension
            throw e;
        }
    }

    /**
     * The handshake, taken by the shortcut RFC 8620 leaves open.
     *
     * The specification has the server push a PushVerification object to the endpoint
     * and the client echo the code back, which proves the endpoint is reachable. It
     * does not forbid /get from returning verificationCode, and Stalwart returns it in
     * the default property set, so the code can be read and echoed here with no browser
     * round trip and no dependence on a push actually arriving.
     *
     * That is compliant and it is convenient, and it matters that it also defeats the
     * point of the handshake: a verified subscription here proves nothing about
     * deliverability. The only thing that does is pushSeen, set when a service worker
     * tells us a push landed, and nothing may relax the polling fallback before then.
     */
    private boolean verify(String mailbox, String id) {
        ObjectNode args = jmap.newObject();
        args.putArray("ids").add(id);
        ArrayNode properties = args.putArray("properties");
        properties.add("id");
        properties.add("verificationCode");

        ArrayNode calls = jmap.newArray();
        calls.add(jmap.invocation("PushSubscription/get", args, "v0"));
        JsonNode list = jmap.response(jmap.call(mailbox, List.of(JmapClient.CORE), calls),
                "PushSubscription/get", "v0").path("list");
        if (!list.isArray() || list.isEmpty()) return false;

        String code = JmapClient.text(list.get(0), "verificationCode");
        if (code == null || code.isBlank()) return false;

        ObjectNode update = jmap.newObject();
        update.putObject("update").putObject(id).put("verificationCode", code);
        ArrayNode confirm = jmap.newArray();
        confirm.add(jmap.invocation("PushSubscription/set", update, "v1"));
        JsonNode result = jmap.response(jmap.call(mailbox, List.of(JmapClient.CORE), confirm),
                "PushSubscription/set", "v1");
        return !result.path("updated").path(id).isMissingNode();
    }
}
