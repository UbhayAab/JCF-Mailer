package com.jarurat.mailer.device;

import com.jarurat.mailer.security.LoginAddress;
import com.jarurat.mailer.webmail.MailboxAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * See the devices that can open this mailbox without a password, and take any of them
 * away.
 *
 * This is not decoration on the feature, it is half of it. A token that lives for six
 * months and cannot be withdrawn is a liability rather than a convenience: the moment
 * a phone is lost, the only remedy without this would be changing the mailbox password
 * on the mail server, which signs every other device of every other person in a shared
 * mailbox out at the same time. Revoking one device here deletes its rows, and the
 * sealed credential dies with them.
 *
 * THE MAILBOX IS NEVER A PARAMETER, the rule the webmail endpoints already hold to.
 * The address is taken from the session pin, and only from the signed-in name when no
 * mailbox is open, so there is no device list a signed-in user can read except their
 * own and no phone they can sign out except one of theirs. DeviceTokenService checks
 * the same ownership again before it deletes anything, because a family id travels in
 * a URL and one test in one place is one deploy away from being none.
 *
 * There is no screen for this yet. The templates are not this agent's to change, so
 * these three endpoints are reachable from the console fetch helpers and from curl but
 * nothing renders them; that gap is stated in the report rather than left for somebody
 * to discover. The endpoints are still the right thing to ship first, because the
 * revocation path has to exist before the tokens do.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceApi {

    private final DeviceTokenService tokens;
    private final MailboxAccess mailboxes;
    private final DeviceSettings settings;

    public DeviceApi(DeviceTokenService tokens, MailboxAccess mailboxes, DeviceSettings settings) {
        this.tokens = tokens;
        this.mailboxes = mailboxes;
        this.settings = settings;
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication auth, HttpSession session,
                                          HttpServletRequest request) {
        return tokens.list(callerMailbox(auth, session), DeviceCookie.parse(request).orElse(null))
                .stream()
                .map(DeviceApi::render)
                .toList();
    }

    /**
     * Revoking the device making the request is allowed and is the ordinary way to
     * undo an enrolment from the phone itself, so the cookie is cleared as well.
     * Without that the browser would keep presenting a token whose row has gone and
     * pay for a lookup on every request until it expired.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable String id, Authentication auth,
                                                      HttpSession session, HttpServletRequest request,
                                                      HttpServletResponse response) {
        String mailbox = callerMailbox(auth, session);
        // Asked before the rows go, because afterwards there is nothing left to match
        // this browser's cookie against.
        boolean thisDevice = tokens.list(mailbox, DeviceCookie.parse(request).orElse(null)).stream()
                .anyMatch(device -> device.current() && device.id().equals(id));

        if (!tokens.revoke(mailbox, id)) return ResponseEntity.notFound().build();
        if (thisDevice) DeviceCookie.clear(response, settings.isCookieSecure());
        return ResponseEntity.ok(Map.of("revoked", true));
    }

    @DeleteMapping
    public Map<String, Object> revokeAll(Authentication auth, HttpSession session,
                                         HttpServletResponse response) {
        int devices = tokens.revokeAll(callerMailbox(auth, session));
        DeviceCookie.clear(response, settings.isCookieSecure());
        return Map.of("revoked", devices);
    }

    /**
     * The pinned mailbox first, and the signed-in name only when none is open.
     *
     * The fallback is what lets somebody who has just signed in on a laptop, before
     * touching the mailbox screen, still see and revoke the phones enrolled against
     * their own address. It cannot widen anything: an app_user name is an address the
     * person has already proved a console password for, and a device belonging to a
     * different address is not returned by either path.
     */
    private String callerMailbox(Authentication auth, HttpSession session) {
        String pinned = mailboxes.current(auth, session);
        if (pinned != null) return pinned;
        return auth == null ? "" : LoginAddress.canonical(auth.getName());
    }

    private static Map<String, Object> render(DeviceSummary device) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", device.id());
        row.put("label", device.label());
        row.put("firstSeen", iso(device.firstSeen()));
        row.put("lastSeen", iso(device.lastSeen()));
        row.put("lastIp", device.lastIp());
        row.put("current", device.current());
        return row;
    }

    /** ISO-8601 strings rather than epoch numbers, so the screen can print them as they arrive. */
    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
