package com.jarurat.mailer.push;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The two housekeeping jobs a push table cannot live without.
 *
 * RENEWAL, AND THE SEVEN DAY CEILING IT EXISTS FOR
 * ------------------------------------------------------------------------------
 * Stalwart hard clamps a PushSubscription to seven days and renewing one needs the
 * mailbox credential, which this process only has while somebody has that mailbox
 * open. So this runs daily against a generous window rather than once at the last
 * minute: there has to be more than one chance to catch a morning when somebody
 * actually opened their mail. The practical effect is that new mail notifications
 * keep working for as long as each mailbox is opened at least once a week, and stop
 * quietly a week after it is not.
 *
 * That last sentence is the failure this design has to be honest about, so it is not
 * left to a log. The expiry is on the row, GET /api/mail/push/config returns it, and
 * the settings screen is expected to say when notifications will stop rather than
 * letting a person discover it by not being told about a referral.
 *
 * PRUNING, AND WHY IT CANNOT BE ONLY 404 AND 410
 * ------------------------------------------------------------------------------
 * Those two are the honest answer and PushService deletes on them immediately, which
 * handles almost everything. What it does not handle is a push service that answers
 * 400 or 403 forever for an endpoint whose browser profile was deleted, and there are
 * several that do. Without a second rule the table only ever grows, and it grows
 * fastest on exactly the rows that will never work again.
 */
@Component
public class PushMaintenance {

    private final PushSubscriptionRepository subscriptions;
    private final JmapPushRegistrar registrar;
    private final PushHealth health;
    private final int deadAfterFailures;
    private final Duration deadAfter;

    public PushMaintenance(PushSubscriptionRepository subscriptions,
                           JmapPushRegistrar registrar,
                           PushHealth health,
                           @Value("${jarurat.push.dead-after-failures:20}") int deadAfterFailures,
                           @Value("${jarurat.push.dead-after-days:30}") int deadAfterDays) {
        this.subscriptions = subscriptions;
        this.registrar = registrar;
        this.health = health;
        this.deadAfterFailures = deadAfterFailures;
        this.deadAfter = Duration.ofDays(Math.max(1, deadAfterDays));
    }

    /**
     * Every six hours rather than daily, because a renewal can only succeed while the
     * mailbox is open and four attempts a day catch four different times of day.
     */
    @Scheduled(initialDelay = 120_000, fixedDelay = 6 * 3_600_000)
    public void renewRegistrations() {
        // Three days of headroom against a seven day ceiling. Anything tighter and a
        // long weekend with nobody signing in is enough to lose the registration.
        Instant cutoff = Instant.now().plus(Duration.ofDays(3));
        for (PushSubscriptionRecord row : subscriptions.dueForRenewal(cutoff)) {
            boolean renewed = registrar.renew(row.getMailbox(), row);
            if (!renewed && row.getJmapExpiresAt() != null
                    && row.getJmapExpiresAt().isBefore(Instant.now())) {
                // Already lapsed and we cannot reach the credential to fix it. Recorded
                // rather than deleted: the subscription is still perfectly good for the
                // notifications this application raises itself.
                health.record(row.getMailbox(), "JMAP_LAPSED",
                        "New mail notifications for one device have lapsed at the mail server. "
                                + "Opening this mailbox on any device restores them.");
            }
        }
    }

    @Scheduled(initialDelay = 900_000, fixedDelay = 24 * 3_600_000)
    public void pruneDeadSubscriptions() {
        List<PushSubscriptionRecord> dead =
                subscriptions.deadWeight(deadAfterFailures, Instant.now().minus(deadAfter));
        for (PushSubscriptionRecord row : dead) {
            registrar.unregister(row.getMailbox(), row);
            subscriptions.deleteById(row.getId());
            health.recordPruned();
        }
    }
}
