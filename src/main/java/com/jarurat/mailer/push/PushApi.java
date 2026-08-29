package com.jarurat.mailer.push;

import com.jarurat.mailer.webmail.MailboxAccess;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The four calls a browser makes to arrange its own notifications.
 *
 * Every one of them acts on the mailbox pinned to this browser session and nothing
 * else. There is no mailbox parameter anywhere here, for exactly the reason
 * MailboxAccess exists: a signed-in person must not be able to reach another mailbox
 * by editing a request, and they would have to go through MailboxAccess.open and
 * produce that mailbox's password to get anywhere near one. The device id does come
 * from the browser, so it is only ever used as half of a key whose other half is the
 * pinned address.
 *
 * These live under /api/mail rather than a path of their own so that the mail-only
 * session rule in SecurityConfig already covers them. Somebody who signed in with only
 * a mailbox password can reach /api/mail/**, which is the whole population this
 * feature is for, and moving them anywhere else would put them behind the console
 * permissions that population does not have.
 */
@RestController
@RequestMapping("/api/mail/push")
public class PushApi {

    private final MailboxAccess mailboxes;
    private final PushService push;
    private final JmapPushRegistrar registrar;
    private final VapidKeys keys;
    private final PushHealth health;

    public PushApi(MailboxAccess mailboxes, PushService push, JmapPushRegistrar registrar,
                   VapidKeys keys, PushHealth health) {
        this.mailboxes = mailboxes;
        this.push = push;
        this.registrar = registrar;
        this.keys = keys;
        this.health = health;
    }

    /** What the browser needs before it can subscribe, and what to say if it cannot. */
    @GetMapping("/config")
    public Map<String, Object> config(Authentication auth, HttpSession session,
                                      @RequestParam(required = false) String deviceClientId,
                                      @RequestParam(required = false) String deviceId) {
        String mailbox = mailboxes.require(auth, session);
        // Two spellings accepted because two callers use them. deviceClientId is the
        // name RFC 8620 gives this field and the name the page and the service worker
        // both send; deviceId is what this package calls it internally. Answering only
        // one of them would be a contract mismatch that shows up as notifications
        // simply never switching on, with a 200 on every request.
        return state(mailbox, deviceClientId != null ? deviceClientId : deviceId);
    }

    /**
     * Exactly what PushSubscription.toJSON() produces, plus a device id the browser
     * keeps for itself so that the same laptop is recognised after a reload.
     */
    public record SubscribeRequest(String deviceClientId, String deviceId,
                                   String endpoint, Map<String, String> keys) {
        /** RFC 8620 calls it deviceClientId, and that is what the browser sends. */
        String device() { return deviceClientId != null ? deviceClientId : deviceId; }
    }

    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(Authentication auth, HttpSession session,
                                       @RequestBody SubscribeRequest body) {
        String mailbox = mailboxes.require(auth, session);
        if (!keys.enabled()) {
            // Told plainly rather than stored and never used. A subscription this
            // server can never sign for is worse than no subscription, because the
            // browser would show notifications as on and nothing would ever arrive.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", keys.disabledReason()));
        }
        if (body == null || blank(body.device()) || blank(body.endpoint()) || body.keys() == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "A subscription needs a device id, an endpoint and both keys."));
        }

        PushSubscriptionRecord row;
        try {
            row = push.subscribe(mailbox, body.device().trim(), body.endpoint().trim(),
                    body.keys().get("p256dh"), body.keys().get("auth"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        // Best effort, and deliberately not fatal. Registering at the mail server is
        // what makes new mail reach a closed browser; failing it costs that and leaves
        // everything this application raises itself working, so it must not turn into
        // a failed subscribe. Whether it worked is in the answer below.
        registrar.register(mailbox, row);

        return ResponseEntity.ok(state(mailbox, body.device().trim()));
    }

    /**
     * verificationCode is accepted and ignored on purpose. A service worker that sees a
     * PushVerification before this server has finished its own handshake may echo the
     * code back here, and answering that with a 400 would make a race look like a bug.
     * The handshake is already completed server side against the mail server, so the
     * only thing this call means either way is that a push arrived.
     */
    public record DeviceRequest(String deviceClientId, String deviceId, String verificationCode) {
        String device() { return deviceClientId != null ? deviceClientId : deviceId; }
    }

    /**
     * The service worker reporting that a push actually arrived.
     *
     * This is the only proof of deliverability there is, because the registration
     * handshake at the mail server completes without a push having to be delivered.
     * Until this has been called once, the polling fallback stays at its full rate.
     */
    @PostMapping("/seen")
    public ResponseEntity<?> seen(Authentication auth, HttpSession session,
                                  @RequestBody DeviceRequest body) {
        String mailbox = mailboxes.require(auth, session);
        if (body == null || blank(body.device())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Which device?"));
        }
        boolean known = push.markSeen(mailbox, body.device().trim());
        return ResponseEntity.ok(Map.of("ok", known));
    }

    @DeleteMapping("/subscribe")
    public ResponseEntity<?> unsubscribe(Authentication auth, HttpSession session,
                                         @RequestBody DeviceRequest body) {
        String mailbox = mailboxes.require(auth, session);
        if (body == null || blank(body.device())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Which device?"));
        }
        String deviceId = body.device().trim();
        push.device(mailbox, deviceId).ifPresent(row -> registrar.unregister(mailbox, row));
        push.unsubscribe(mailbox, deviceId);
        return ResponseEntity.ok(state(mailbox, deviceId));
    }

    // ------------------------------------------------------------------

    /**
     * One shape for all four answers, so the browser has a single thing to read.
     *
     * installRequired is not here and is not going to be. Whether an iPhone has this
     * site on its Home Screen is knowable in the browser in one line and is not
     * knowable on the server without sniffing a user agent, which is a guess dressed
     * as a fact. The client decides that one.
     */
    private Map<String, Object> state(String mailbox, String deviceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supported", keys.enabled());
        out.put("applicationServerKey", keys.applicationServerKey());
        if (!keys.enabled()) out.put("reason", keys.disabledReason());

        Optional<PushSubscriptionRecord> row = blank(deviceId)
                ? Optional.empty()
                : push.device(mailbox, deviceId.trim());

        // active only once a push has genuinely landed on this device. Everything
        // before that is pending, because the registration handshake completes without
        // a push ever being delivered and calling that active would be a claim we
        // cannot support.
        out.put("state", row.map(r -> !keys.enabled() ? "off" : r.isPushSeen() ? "active" : "pending")
                .orElse("off"));
        out.put("devices", push.devicesFor(mailbox).size());
        // Whether the mail server will put a sender and a subject inside the payload,
        // rather than only telling the device that something arrived. False is not a
        // failure, it is a less useful notification, and the screen should say which.
        out.put("emailPush", row.map(PushSubscriptionRecord::isEmailPush).orElse(false));

        row.ifPresent(r -> {
            out.put("pushSeen", r.isPushSeen());
            out.put("newMailRegistered", r.getJmapSubscriptionId() != null);
            out.put("newMailVerified", r.isJmapVerified());
            // The seven day ceiling, surfaced rather than left to expire quietly. The
            // settings screen is expected to say "notifications will stop on this date
            // unless you open your mailbox" once it is inside about two days.
            out.put("expiresAt", r.getJmapExpiresAt() == null
                    ? null : r.getJmapExpiresAt().toString());
            out.put("lastSuccessAt", r.getLastSuccessAt() == null
                    ? null : r.getLastSuccessAt().toString());
            out.put("failureCount", r.getFailureCount());
            out.put("lastError", r.getLastError());
        });

        // The whole point of PushHealth: a broken fan-out is silent everywhere else, so
        // it is put on the one call the settings screen already makes.
        List<Map<String, Object>> recent = new ArrayList<>();
        for (PushHealth.Failure failure : health.recentFor(mailbox)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("at", failure.at().toString());
            item.put("kind", failure.kind());
            item.put("status", failure.status());
            item.put("detail", failure.detail());
            // The endpoint itself is a bearer capability and never leaves the database.
            item.put("device", failure.endpointHash() == null
                    ? null : failure.endpointHash().substring(0, 8));
            recent.add(item);
        }
        out.put("recentFailures", recent);
        out.put("asOf", Instant.now().toString());
        return out;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Same answer as every other mail endpoint gives when no mailbox is open, so the
     * screen asks for one rather than showing an error.
     */
    @ExceptionHandler(MailboxAccess.MailboxLockedException.class)
    public ResponseEntity<?> onLocked(MailboxAccess.MailboxLockedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("locked", true, "error", e.getMessage()));
    }
}
