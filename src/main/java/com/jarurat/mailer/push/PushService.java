package com.jarurat.mailer.push;

import com.jarurat.mailer.models.MailboxSettings;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fan-out. Takes one notification and one mailbox, and gets it to every device that
 * mailbox has registered without making anybody wait.
 *
 * WHY THE CALLER NEVER WAITS
 * ------------------------------------------------------------------------------
 * A push goes to fcm.googleapis.com or web.push.apple.com over the public internet.
 * Those are usually fast and occasionally are not, and there is no arrangement of
 * timeouts that makes "usually fast" safe to put inside a request or inside the outbox
 * loop: five devices behind one slow endpoint at ten seconds apiece is nearly a minute
 * of a send path spent waiting on Google. So every send is submitted and nothing is
 * joined. The caller gets a future it is free to ignore, and the tests are the only
 * thing that ever joins it.
 *
 * One dead endpoint also must not delay the others, which is why each subscription is
 * its own task rather than a loop inside one. The application already runs on virtual
 * threads, so a hundred devices is a hundred parked threads and a few hundred KB, not
 * a thread pool that has to be sized.
 */
@Service
public class PushService {

    private final PushSubscriptionRepository subscriptions;
    private final WebPushSender sender;
    private final PushHealth health;
    private final VapidKeys keys;
    private final int ttlSeconds;

    /**
     * Its own executor rather than the shared one, so that a push service having a bad
     * hour cannot occupy threads anything else in the application needs. Virtual
     * threads make that essentially free.
     */
    private final ExecutorService fanOut = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * How the rules row is read, and why it is not a constructor argument.
     *
     * Field injection rather than a sixth constructor parameter because the constructor
     * is called by hand in tests that own no database and have no business growing one,
     * and because a notification that cannot reach the rules must still be sent. An
     * entity manager that is null is therefore a supported state and not a bug: it means
     * the rules could not be consulted, which rulesFor reports as null and every caller
     * has to have an answer for. It goes through the entity manager rather than a Spring
     * Data interface for the reason NotificationRuleApi writes down at length: a
     * repository would be a new file in a package this phase does not own, and find
     * against a single primary key is exactly what one would have generated.
     */
    @PersistenceContext
    private EntityManager em;

    public PushService(PushSubscriptionRepository subscriptions, WebPushSender sender,
                       PushHealth health, VapidKeys keys,
                       @Value("${jarurat.push.ttl-seconds:86400}") int ttlSeconds) {
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.health = health;
        this.keys = keys;
        this.ttlSeconds = ttlSeconds;
    }

    @PreDestroy
    void stop() {
        // Nothing is joined, so shutdown rather than shutdownNow: a message already on
        // its way is worth the two seconds it takes to finish.
        fanOut.shutdown();
    }

    // ------------------------------------------------------------------
    // The triggers this application can actually fire today
    // ------------------------------------------------------------------

    /**
     * The highest ranked notification in the whole design, and the only one this
     * application can raise entirely on its own.
     *
     * A scheduled message failing at six in the morning is invisible today: the person
     * believes it went, and nothing tells them otherwise until they go looking. It is
     * sticky on purpose, because it is the one mail event where dismissing the
     * notification by accident loses the only warning there is.
     *
     * The lane is asked for and no longer assumed. This method used to build lane A
     * unconditionally, which is how a mailbox with quiet hours switched on was woken at
     * three in the morning by a send that could not usefully be retried until eight, and
     * why decideSendFailure existed for a while with nothing calling it. Quiet hours are
     * the only thing that moves this one, because a failed send has no folder, no sender
     * and no recipient list to judge; see decideSendFailure.
     */
    public CompletableFuture<List<PushDelivery>> sendFailed(String mailbox, long queuedId,
                                                            String subject, String recipient,
                                                            String sentence) {
        NotificationRules rules = rulesFor(mailbox);
        String lane;
        if (rules == null) {
            // The one place in this application where interrupting without having asked
            // is the right answer, written down rather than left to fall out of a
            // default. Reaching here means the rules could not be read at all, which is
            // the database being unreachable rather than anybody's preference. A failed
            // send is a handful of events a week, this is the only channel that reports
            // it, and there is no screen that will bring it up later. Every other lane in
            // this application errs the other way, towards silence.
            lane = PushNotification.LANE_INTERRUPT;
        } else {
            // code() and not a second mapping written here: Lane says which letter it is,
            // once, next to the definition. A send failure is never lane C, because
            // decideSendFailure only ever interrupts or is quieted to lane B.
            lane = rules.decideSendFailure(Instant.now()).lane().code();
        }

        String body = (recipient == null || recipient.isBlank() ? "" : "To " + recipient + ", ")
                + "\"" + (subject == null || subject.isBlank() ? "(no subject)" : subject) + "\"\n"
                + (sentence == null ? "It was not delivered." : sentence);

        // Built here rather than through PushNotification.interrupt or .deliver, because
        // neither carries the combination this one notification needs: the lane is
        // whatever the rules just said, while renotify and requireInteraction stay on in
        // both of them. Quiet hours take the sound off a notification. They do not make
        // it dismissable by accident, and this is the message where being dismissed by
        // accident loses the only warning there is.
        return notify(mailbox, new PushNotification(
                "send-failed",
                lane,
                "Message not sent",
                body,
                "jm-fail:" + queuedId,
                true,
                true,
                System.currentTimeMillis(),
                java.util.Map.of("kind", "outbox", "url", "/mail?outbox=" + queuedId)));
    }

    /**
     * The notification rules in force for a mailbox, or null when they could not be read.
     *
     * Null and default are deliberately different answers. A mailbox that has never
     * opened the settings sheet has no row, and the right rules for it are the defaults
     * a fresh NotificationRules carries - Inbox on Direct, quiet from 21:00 to 08:00 -
     * because those are the same rules the settings screen would show that person. Null
     * means something else entirely: the question could not be put. Callers must say so
     * rather than quietly picking a lane, which is the failure this whole change exists
     * to undo.
     *
     * Read outside a transaction on purpose. The collections on NotificationRules are
     * eager precisely so that a rule can be evaluated from a poll or a fan-out that
     * holds no transaction, and wrapping either of those in one would mean holding a
     * database connection across a JMAP or a Web Push round trip.
     *
     * It is public and lives here because the poll path needs the same lookup and a
     * second copy of it, or a repository file in a package this phase does not own,
     * would both be worse than one method with the reason attached.
     */
    public NotificationRules rulesFor(String mailbox) {
        if (em == null) return null;
        try {
            NotificationRules found = em.find(NotificationRules.class,
                    MailboxSettings.normaliseAddress(mailbox));
            return found == null ? new NotificationRules(mailbox) : found;
        } catch (RuntimeException e) {
            // A notification is worth more than the reason its rules could not be loaded,
            // so this reports "could not ask" and lets the caller decide, rather than
            // taking down the outbox sweep or the poll that called it.
            return null;
        }
    }

    /**
     * Submits one notification to every device this mailbox has registered and returns
     * without waiting for any of them.
     */
    public CompletableFuture<List<PushDelivery>> notify(String mailbox, PushNotification notification) {
        String key = key(mailbox);
        if (!keys.enabled()) {
            // Not an error and not worth a failure row. Push being unconfigured is a
            // supported state, and the reason for it is already on the config endpoint.
            return CompletableFuture.completedFuture(List.of());
        }

        List<PushSubscriptionRecord> devices = subscriptions.findByMailbox(key);
        if (devices.isEmpty()) return CompletableFuture.completedFuture(List.of());

        byte[] payload = notification.toPayload();
        String urgency = notification.urgency();
        Instant now = Instant.now();

        List<CompletableFuture<PushDelivery>> pending = new ArrayList<>(devices.size());
        for (PushSubscriptionRecord device : devices) {
            if (!device.sendableAt(now)) continue;   // still inside a 429 back-off
            pending.add(CompletableFuture.supplyAsync(() -> deliver(key, device, payload, urgency), fanOut));
        }

        return CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> pending.stream().map(CompletableFuture::join).toList());
    }

    /**
     * One device, and everything that follows from what the push service said.
     *
     * This is the only place a subscription is deleted, and the rule is narrow on
     * purpose: 404 and 410 mean gone and nothing else does. A 403 usually means the
     * VAPID key pair has been changed, which is a configuration mistake, and deleting
     * the fleet's subscriptions on the way past would turn a five minute fix into a
     * migration in which every person has to re-enable notifications by hand.
     */
    private PushDelivery deliver(String mailbox, PushSubscriptionRecord device,
                                 byte[] payload, String urgency) {
        health.recordAttempt();
        PushDelivery result;
        try {
            result = sender.send(device, payload, urgency, ttlSeconds);
        } catch (RuntimeException e) {
            // A fan-out task that throws would otherwise complete the whole batch
            // exceptionally and take the other devices' results with it.
            result = PushDelivery.of(PushDelivery.Outcome.REJECTED, 0, String.valueOf(e.getMessage()));
        }

        try {
            if (result.dead()) {
                subscriptions.deleteById(device.getId());
                health.recordPruned();
            } else if (result.delivered()) {
                device.recordSuccess(Instant.now());
                subscriptions.save(device);
            } else {
                device.recordFailure(Instant.now(), result.status(), result.detail(), result.retryAfter());
                subscriptions.save(device);
            }
        } catch (RuntimeException e) {
            // Losing the bookkeeping must not lose the delivery, and a row another
            // thread has already deleted is the normal way to get here.
            health.record(mailbox, "BOOKKEEPING", String.valueOf(e.getMessage()));
        }

        health.record(mailbox, device, result);
        return result;
    }

    // ------------------------------------------------------------------
    // Subscription lifecycle
    // ------------------------------------------------------------------

    /**
     * Stores or repoints one device's subscription.
     *
     * The mailbox always comes from MailboxAccess and never from the request body, so
     * there is no argument here a caller could use to write into somebody else's row.
     * The device id does come from the browser, which is why it is only ever used
     * together with the mailbox.
     */
    public PushSubscriptionRecord subscribe(String mailbox, String deviceId, String endpoint,
                                            String uaPublic, String authSecret) {
        String key = key(mailbox);
        validate(endpoint, uaPublic, authSecret);

        Optional<PushSubscriptionRecord> existing = subscriptions.findByMailboxAndDeviceId(key, deviceId);
        if (existing.isPresent()) {
            PushSubscriptionRecord row = existing.get();
            row.repoint(endpoint, uaPublic, authSecret);
            return subscriptions.save(row);
        }
        return subscriptions.save(
                new PushSubscriptionRecord(key, deviceId, endpoint, uaPublic, authSecret));
    }

    /**
     * Refuses anything that cannot be encrypted for later.
     *
     * Checked at the door rather than at send time because a row that will never
     * encrypt is a row that generates one failure per notification forever, and the
     * person it belongs to sees nothing at all while it does.
     */
    private void validate(String endpoint, String uaPublic, String authSecret) {
        if (endpoint == null || !endpoint.startsWith("https://")) {
            throw new IllegalArgumentException("A push endpoint has to be an https URL.");
        }
        if (endpoint.length() > 2000) {
            throw new IllegalArgumentException("That push endpoint is too long to store.");
        }
        byte[] point;
        byte[] auth;
        try {
            point = VapidKeys.decode(uaPublic);
            auth = VapidKeys.decode(authSecret);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Those subscription keys are not base64url.");
        }
        if (point.length != 65 || point[0] != 0x04) {
            throw new IllegalArgumentException("p256dh has to be a 65 byte uncompressed P-256 point.");
        }
        if (auth.length != 16) {
            throw new IllegalArgumentException("auth has to be 16 bytes.");
        }
    }

    public void unsubscribe(String mailbox, String deviceId) {
        subscriptions.deleteByMailboxAndDeviceId(key(mailbox), deviceId);
    }

    /** Sign out means every device this mailbox registered stops being notified. */
    public void forgetMailbox(String mailbox) {
        subscriptions.deleteByMailbox(key(mailbox));
    }

    public List<PushSubscriptionRecord> devicesFor(String mailbox) {
        return subscriptions.findByMailbox(key(mailbox));
    }

    public Optional<PushSubscriptionRecord> device(String mailbox, String deviceId) {
        return subscriptions.findByMailboxAndDeviceId(key(mailbox), deviceId);
    }

    /**
     * The service worker telling us a push genuinely arrived.
     *
     * This is the only evidence that the chain works end to end, because the
     * registration handshake at the mail server can be completed without a push ever
     * being delivered. Nothing may lengthen the polling interval until this is true.
     */
    public boolean markSeen(String mailbox, String deviceId) {
        return subscriptions.findByMailboxAndDeviceId(key(mailbox), deviceId)
                .map(row -> {
                    row.setPushSeen(true);
                    subscriptions.save(row);
                    return true;
                })
                .orElse(false);
    }

    static String key(String mailbox) {
        return mailbox == null ? "" : mailbox.trim().toLowerCase(Locale.ROOT);
    }
}
