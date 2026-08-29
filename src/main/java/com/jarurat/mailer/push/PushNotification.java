package com.jarurat.mailer.push;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The thing a service worker is handed. One shape, whatever produced it.
 *
 * The notification design keeps two lanes apart and the only lever the web platform
 * offers for the distinction is silent, which suppresses sound and vibration whatever
 * the device is set to. So the lane is carried here rather than decided in the worker:
 * a browser cannot know whether a message was addressed to a person or to an alias,
 * and shipping the rules it would need to work that out means shipping somebody's VIP
 * list to the browser every time.
 *
 * WHAT GOES IN THE PAYLOAD, AND WHAT DOES NOT
 * ------------------------------------------------------------------------------
 * The body crosses Google's or Apple's push service. It is encrypted end to end
 * between this server and one browser installation, which is what makes carrying a
 * subject line across it acceptable at all. It still travels, so the rule is that
 * nothing goes in here that is not already going to be shown on a lock screen anyway:
 * a sender, a subject, a preview. Never an attachment, never a message body, never a
 * recipient list, never anything that identifies a patient beyond what the sender
 * themselves put in the subject. There is no image field for the same reason the
 * notification design refuses one: the only candidate content is a scan or a
 * prescription rendered full width on a lock screen in a waiting room.
 */
record PushNotification(String type, String lane, String title, String body,
                        String tag, boolean renotify, boolean requireInteraction,
                        long timestamp, Map<String, String> data) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Lane A interrupts. Urgency high, so a push service wakes a sleeping phone. */
    static final String LANE_INTERRUPT = "A";

    /** Lane B is delivered silently and completely. */
    static final String LANE_DELIVER = "B";

    static PushNotification interrupt(String type, String title, String body, String tag,
                                      boolean sticky, Map<String, String> data) {
        return new PushNotification(type, LANE_INTERRUPT, title, body, tag, true, sticky,
                System.currentTimeMillis(), data);
    }

    static PushNotification deliver(String type, String title, String body, String tag,
                                    Map<String, String> data) {
        return new PushNotification(type, LANE_DELIVER, title, body, tag, false, false,
                System.currentTimeMillis(), data);
    }

    /**
     * high for lane A, normal for lane B.
     *
     * This header is not encrypted, so the push service learns that this application
     * considers one message more important than another. That is the price of a phone
     * that wakes up for a hospital and does not wake up for a payslip, and it is a
     * price worth paying because the alternative is every notification arriving with
     * the same urgency and none of them arriving promptly.
     */
    String urgency() {
        return LANE_INTERRUPT.equals(lane) ? "high" : "normal";
    }

    /**
     * The encrypted bytes, trimmed to fit one record.
     *
     * Trimming happens on the body rather than by refusing to send, because a push
     * that arrives with a shortened preview is worth far more than one that does not
     * arrive. The title is never trimmed: it is the sender, it is the one line that
     * says who this is from, and a truncated name is worse than a truncated preview.
     */
    byte[] toPayload() {
        ObjectNode root = JSON.createObjectNode();
        root.put("v", 1);
        root.put("type", type);
        root.put("lane", lane);
        root.put("title", title == null ? "" : title);
        root.put("tag", tag == null ? "" : tag);
        root.put("renotify", renotify);
        root.put("requireInteraction", requireInteraction);
        root.put("silent", LANE_DELIVER.equals(lane));
        root.put("timestamp", timestamp);

        ObjectNode payloadData = root.putObject("data");
        if (data != null) {
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(data).entrySet()) {
                if (entry.getValue() != null) payloadData.put(entry.getKey(), entry.getValue());
            }
        }

        String text = body == null ? "" : body;
        byte[] encoded = encode(root, text);
        while (encoded.length > WebPushCrypto.MAX_PLAINTEXT && !text.isEmpty()) {
            // Bytes and not characters: a Devanagari subject is three bytes a
            // character, so trimming by character count would loop far more times than
            // it needs to and could still overshoot on the last step.
            int keep = Math.max(0, text.length() - Math.max(8,
                    (encoded.length - WebPushCrypto.MAX_PLAINTEXT) / 2));
            // Never cut between the halves of a surrogate pair. An emoji in a subject
            // line would otherwise become an unpaired code unit, and Jackson writes
            // that as a replacement character that then travels to the lock screen.
            if (keep > 0 && Character.isHighSurrogate(text.charAt(keep - 1))) keep--;
            text = text.substring(0, keep);
            encoded = encode(root, text);
        }
        return encoded;
    }

    private static byte[] encode(ObjectNode root, String text) {
        root.put("body", text);
        return JSON.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    }
}
