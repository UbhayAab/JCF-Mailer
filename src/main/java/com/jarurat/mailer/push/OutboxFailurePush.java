package com.jarurat.mailer.push;

import com.jarurat.mailer.models.QueuedMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Turns a failed send into a notification on somebody's phone.
 *
 * This is the highest ranked trigger in the notification design and the only one this
 * application can raise without help, because the failure happens inside this process.
 * It is also the app's worst current silence: a message scheduled at eleven at night
 * to go at six in the morning fails at six in the morning with nobody watching, the
 * outbox screen quietly grows a red row, and the person carries on believing the mail
 * went. There is no other channel that tells them.
 *
 * WHY THIS POLLS THE TABLE INSTEAD OF BEING CALLED BY OUTBOXSERVICE
 * ------------------------------------------------------------------------------
 * A call from the place that settles the row would be immediate and exact, and it is
 * the right answer. It is not this answer because that file belongs to another part of
 * the tree and this package may not reach into it. Polling a bounded page every half
 * minute costs one indexed query against a table that is already swept every two
 * seconds by the outbox itself, so the cost is not the objection; the delay is, and
 * thirty seconds on a notification about a message that has already failed is not a
 * delay anybody can perceive. If this ever moves, it becomes one line at
 * OutboxService line 469 and this class deletes.
 *
 * Nothing before the process started is pushed. A restart is not news, and waking
 * somebody at nine in the morning about a message that failed last Thursday would
 * teach them to ignore the whole channel. The outbox screen already carries the
 * backlog, and countUnseenFailures already puts a number on it.
 */
@Component
public class OutboxFailurePush {

    /** One notification per device per failure, and never more than this in one tick. */
    private static final int MAX_PER_TICK = 5;

    private final FailedSendRepository failures;
    private final PushService push;

    /**
     * Where the last sweep got to. Held in memory on purpose: its only job is to stop
     * the same failure being pushed twice inside one run, and persisting it would turn
     * a restart into a replay of everything that failed while the process was down.
     */
    private volatile LocalDateTime since = LocalDateTime.now();

    public OutboxFailurePush(FailedSendRepository failures, PushService push) {
        this.failures = failures;
        this.push = push;
    }

    /**
     * Deliberately behind OutboxService's own 30 second initial delay, so the first
     * sweep cannot run against rows the outbox is still settling as the app comes up.
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 30_000)
    public void sweep() {
        List<QueuedMessage> settled = failures.failedSince(since, PageRequest.of(0, MAX_PER_TICK));
        if (settled.isEmpty()) return;

        for (QueuedMessage row : settled) {
            String recipient = row.getTo().isEmpty() ? null : row.getTo().get(0);
            // The fan-out is not joined. A push service having a slow minute must not
            // hold up the sweep, and the sweep runs again in thirty seconds anyway.
            push.sendFailed(row.getMailbox(), row.getId(), row.getSubject(), recipient,
                    row.getLastError());
            if (row.getSettledAt() != null && row.getSettledAt().isAfter(since)) {
                since = row.getSettledAt();
            }
        }
    }
}
