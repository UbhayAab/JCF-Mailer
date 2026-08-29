package com.jarurat.mailer.push;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where a broken push fan-out becomes visible to a person rather than to a log file.
 *
 * The brief for this feature is explicit that a failure has to show up somewhere
 * somebody looks, and it is explicit because of what happened last time: five scripts
 * were built, left unreferenced, and each degraded silently to doing nothing, so to
 * the owner they looked like they had never been written. Push has exactly that shape.
 * It runs on a background thread, on behalf of a device nobody is holding, against a
 * service we do not control, and every one of its failure modes is silent by
 * construction. A 403 because somebody rotated the VAPID keys, a 429 that never lifts,
 * a Stalwart registration that expired last Tuesday: none of them throws anywhere a
 * request can see, and none of them stops the mail client working.
 *
 * So the last few failures are kept in memory and returned from
 * GET /api/mail/push/config, which is the call the settings screen already makes. The
 * screen can then say "the last three notifications to this device were refused" in
 * the place a person goes when they think notifications are broken. It is deliberately
 * in memory and deliberately small: this is a symptom display, not an audit trail, and
 * AuditService already owns the durable record of anything that matters.
 */
@Component
public class PushHealth {

    /** Enough to see a pattern, few enough to read at a glance. */
    private static final int KEPT = 20;

    private final Deque<Failure> failures = new ArrayDeque<>();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong pruned = new AtomicLong();

    /**
     * One thing that went wrong, named by the endpoint hash and never the endpoint.
     * The endpoint is a bearer capability: anyone holding it can make that device show
     * a notification, so it does not go into a structure whose whole purpose is to be
     * rendered onto a screen.
     */
    public record Failure(Instant at, String mailbox, String endpointHash,
                          String kind, int status, String detail) {}

    void recordAttempt() { sent.incrementAndGet(); }

    void recordPruned() { pruned.incrementAndGet(); }

    void record(String mailbox, PushSubscriptionRecord subscription, PushDelivery result) {
        if (result.delivered()) {
            delivered.incrementAndGet();
            return;
        }
        add(new Failure(Instant.now(), mailbox,
                subscription == null ? null : subscription.getEndpointHash(),
                result.outcome().name(), result.status(), result.detail()));
    }

    /** For failures that have no subscription behind them, like a JMAP registration. */
    public void record(String mailbox, String kind, String detail) {
        add(new Failure(Instant.now(), mailbox, null, kind, 0, detail));
    }

    private void add(Failure failure) {
        synchronized (failures) {
            failures.addFirst(failure);
            while (failures.size() > KEPT) failures.removeLast();
        }
    }

    /** Newest first, and only this mailbox's own. */
    public List<Failure> recentFor(String mailbox) {
        List<Failure> out = new ArrayList<>();
        synchronized (failures) {
            for (Failure failure : failures) {
                if (failure.mailbox() != null && failure.mailbox().equalsIgnoreCase(mailbox)) {
                    out.add(failure);
                }
            }
        }
        return out;
    }

    public long attempted() { return sent.get(); }

    public long delivered() { return delivered.get(); }

    public long pruned() { return pruned.get(); }
}
