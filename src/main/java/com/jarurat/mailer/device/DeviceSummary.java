package com.jarurat.mailer.device;

import java.time.Instant;

/**
 * One device as a person sees it on the devices list.
 *
 * The identifier here is the family, not a row, because a row is an implementation
 * detail that changes on every use and nobody can revoke a moving target. Everything
 * else on this record is what makes the list answerable: a label to recognise, a
 * first seen to say when it joined, a last seen to say whether it is still in use,
 * and the address it was last used from. No token material appears here and none ever
 * should; this record is rendered to a browser.
 */
public record DeviceSummary(
        String id,
        String label,
        Instant firstSeen,
        Instant lastSeen,
        String lastIp,
        boolean current) {
}
