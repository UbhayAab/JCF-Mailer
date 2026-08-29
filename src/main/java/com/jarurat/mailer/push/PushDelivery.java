package com.jarurat.mailer.push;

import java.time.Instant;

/**
 * What one push service said, reduced to the only five answers that change what this
 * application does next.
 *
 * The status code is kept alongside because a person debugging this on a phone at
 * eleven at night needs the number, but nothing branches on it outside the one place
 * that classifies it. A push service adding a new code should land in REJECTED and be
 * visible, never be silently treated as success.
 */
record PushDelivery(Outcome outcome, int status, String detail, Instant retryAfter) {

    enum Outcome {
        /** Accepted for delivery. Not proof it arrived; only pushSeen is that. */
        DELIVERED,

        /**
         * 404 or 410. The subscription no longer exists and never will again, so the
         * row is deleted rather than retried. Skipping this is how the table grows
         * without limit: a browser profile that was deleted answers 410 forever, on
         * every send, for as long as the row is kept.
         */
        GONE,

        /** 429. Back off for what Retry-After said, and send nothing meanwhile. */
        RATE_LIMITED,

        /**
         * 413. The payload was too large for this service, which caps below our own
         * ceiling. Retrying the same bytes cannot work, so it is recorded and dropped,
         * and the fix is to shorten the notification rather than to try again.
         */
        TOO_LARGE,

        /**
         * Anything else, including 403, which almost always means the VAPID key pair
         * has been changed out from under an existing subscription. It is kept, not
         * pruned, because a mistake in configuration must not delete the fleet's
         * subscriptions on its way past.
         */
        REJECTED,

        /** The push service could not be reached. Transient by assumption. */
        UNREACHABLE,

        /** No VAPID key pair is configured, so nothing was attempted. */
        DISABLED
    }

    boolean delivered() { return outcome == Outcome.DELIVERED; }

    boolean dead() { return outcome == Outcome.GONE; }

    static PushDelivery of(Outcome outcome, int status, String detail) {
        return new PushDelivery(outcome, status, detail, null);
    }
}
