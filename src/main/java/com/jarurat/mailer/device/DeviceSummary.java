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
 *
 * THIS IS NOT THE WIRE SHAPE, and the difference is worth knowing before adding a
 * field here to fix something the screen shows. DeviceApi.render is what the devices
 * sheet actually reads, and it sends the same values under the names that sheet uses
 * as well as under these. Two of the keys it sends have no component here at all: the
 * platform, because DeviceLabel folds the platform and the browser into the one label
 * stored on the row and this record has no business guessing where the seam was, and
 * the mailbox flag, because every token DeviceTokenService issues carries a sealed
 * credential by construction. Both are decided in DeviceApi, where the reasoning sits
 * next to the JSON it produces rather than one layer away from it.
 */
public record DeviceSummary(
        String id,
        String label,
        Instant firstSeen,
        Instant lastSeen,
        String lastIp,
        boolean current) {
}
