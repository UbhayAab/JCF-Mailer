package com.jarurat.mailer.device;

import com.jarurat.mailer.security.LoginAddress;
import com.jarurat.mailer.webmail.MailboxAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 * THE SCREEN IS THE DEVICES SHEET IN mail.html, DRIVEN BY mail.js. That sentence
 * replaces an earlier one here saying there was no screen for this yet, which is the
 * sentence that let this class and that screen drift apart unnoticed. The client
 * shipped against an assumed contract while this class served a different one, and
 * because data.devices read off a bare JSON array is undefined, the sheet reported no
 * devices to everybody and every revoke answered 404. The client is the shipped
 * surface and the one a person can actually see, so it is the fixed point and this
 * file moved to meet it. DeviceApiWireShapeTest pins the answer field by field for
 * that reason: a shape that broke by drifting once will drift again unless something
 * fails when it does.
 *
 * WHY BOTH PREFIXES. "/api/mail/**" is what SecurityConfig leaves open to a session
 * bought with a mailbox password alone, and a mail-only session on a phone is exactly
 * who needs this screen, so the client asks "/api/mail/devices" first. Answering at
 * both costs one array in an annotation and removes a whole class of failure, because
 * a 404 from the first path is indistinguishable, to the client, from the feature not
 * existing at all. MAIL_ONLY_PATHS already lists "/api/devices/**" as well, so neither
 * prefix widens what a mailbox password reaches.
 *
 * WHY BOTH VERBS. The screen posts form bodies, since that is what carries the CSRF
 * token the rest of mail.js already sends, and the DELETE verbs are kept because they
 * are the shape anybody reaching for curl would try first. They are not two
 * behaviours: DELETE /{id} and POST /revoke run the same method and answer the same
 * body.
 */
@RestController
@RequestMapping({ "/api/devices", "/api/mail/devices" })
public class DeviceApi {

    private final DeviceTokenService tokens;
    private final MailboxAccess mailboxes;
    private final DeviceSettings settings;

    public DeviceApi(DeviceTokenService tokens, MailboxAccess mailboxes, DeviceSettings settings) {
        this.tokens = tokens;
        this.mailboxes = mailboxes;
        this.settings = settings;
    }

    /**
     * The list, wrapped in an object rather than sent as a bare array.
     *
     * The wrapper is not ceremony. The client reads data.devices, so a bare array
     * reads as no devices at all, and the enabled flag beside it is the only way a
     * screen can tell "nothing is enrolled" apart from "this deployment turned
     * persistent sign in off", which are the same empty list and want different
     * sentences. The rows are still listed when the feature is off, because rows
     * enrolled before the switch was thrown are precisely the ones somebody would
     * want to withdraw.
     */
    @GetMapping
    public Map<String, Object> list(Authentication auth, HttpSession session,
                                    HttpServletRequest request) {
        List<Map<String, Object>> devices =
                tokens.list(callerMailbox(auth, session), DeviceCookie.parse(request).orElse(null))
                        .stream()
                        .map(DeviceApi::render)
                        .toList();

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("enabled", settings.isEnabled());
        answer.put("devices", devices);
        return answer;
    }

    /**
     * Sign one device out. The form post is what the screen sends; the DELETE below is
     * the same call by another name.
     */
    @PostMapping("/revoke")
    public ResponseEntity<Map<String, Object>> revokeOne(@RequestParam("id") String id, Authentication auth,
                                                         HttpSession session, HttpServletRequest request,
                                                         HttpServletResponse response) {
        return revoke(id, auth, session, request, response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> revokeOneByDelete(@PathVariable String id, Authentication auth,
                                                                 HttpSession session, HttpServletRequest request,
                                                                 HttpServletResponse response) {
        return revoke(id, auth, session, request, response);
    }

    /**
     * Sign out every device except the one asking.
     *
     * Every device except this one, rather than every device, because that is what the
     * screen offering this promises in the sentence above its own button: it counts
     * the rows that are not current, labels the button with that count, and tells the
     * person this device stays signed in. Revoking this one too would make all three
     * of those false and would bounce somebody who only wanted to sign a lost phone
     * out to the login page instead. Somebody who does want everything gone signs this
     * device out from its own row afterwards, which is a real button and does end the
     * session.
     *
     * A caller with no device cookie has no current row, so nothing is excluded and
     * this is every device, which is the right reading of "sign out my other devices"
     * asked from a browser that is not one of them.
     */
    @PostMapping("/revoke-all")
    public Map<String, Object> revokeOthers(Authentication auth, HttpSession session,
                                            HttpServletRequest request) {
        String mailbox = callerMailbox(auth, session);
        int signedOut = 0;
        for (DeviceSummary device : tokens.list(mailbox, DeviceCookie.parse(request).orElse(null))) {
            if (device.current()) continue;
            if (tokens.revoke(mailbox, device.id())) signedOut++;
        }

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("ok", true);
        answer.put("signedOut", signedOut);
        // Never true here by construction, and sent anyway because the client branches
        // on it to decide whether to leave for the login page. An absent flag and a
        // false one have to mean the same thing to it, and saying so outright is
        // cheaper than depending on that.
        answer.put("self", false);
        return answer;
    }

    /**
     * The panic button: every device for this mailbox, including the one asking.
     *
     * Kept as the DELETE verb alone, and deliberately not what the screen's button
     * calls, so that two revoke-everything shapes are not silently different things
     * wearing the same name. This one does end the session it was asked from, the way
     * signing out does.
     */
    @DeleteMapping
    public Map<String, Object> revokeAll(Authentication auth, HttpSession session,
                                         HttpServletRequest request, HttpServletResponse response) {
        int devices = tokens.revokeAll(callerMailbox(auth, session));
        signOutHere(auth, session, request, response);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("ok", true);
        answer.put("revoked", devices);
        answer.put("signedOut", devices);
        answer.put("self", true);
        return answer;
    }

    /**
     * Revoking the device making the request is allowed and is the ordinary way to
     * undo an enrolment from the phone itself, so this browser is signed out with it.
     *
     * WHY 409 AND NOT 404 for an id that revokes nothing. The client tries
     * "/api/mail/devices" and then "/api/devices" and reads a 404 from either as "this
     * is not the path", so a 404 here would not report a stale row, it would make the
     * screen announce that signed-in devices are not available on this server at all.
     * A 409 says the thing the person needs to hear, in a sentence they can read,
     * without lying about which endpoint exists. DeviceTokenService.revoke answers
     * false both for a family that is gone and for one belonging to somebody else, and
     * those stay indistinguishable here on purpose: telling them apart would let
     * anybody test which family ids exist.
     */
    private ResponseEntity<Map<String, Object>> revoke(String id, Authentication auth, HttpSession session,
                                                       HttpServletRequest request, HttpServletResponse response) {
        String mailbox = callerMailbox(auth, session);
        // Asked before the rows go, because afterwards there is nothing left to match
        // this browser's cookie against.
        boolean thisDevice = tokens.list(mailbox, DeviceCookie.parse(request).orElse(null)).stream()
                .anyMatch(device -> device.current() && device.id().equals(id));

        if (!tokens.revoke(mailbox, id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "That device is not signed in any more."));
        }
        if (thisDevice) signOutHere(auth, session, request, response);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("ok", true);
        answer.put("revoked", true);
        answer.put("self", thisDevice);
        return ResponseEntity.ok(answer);
    }

    /**
     * The three things signing out means here, which are the same three things the
     * logout handlers in SecurityConfig do, in the same order.
     *
     * All three are needed and any one alone is a button that lies. Clearing the
     * cookie without ending the session leaves somebody signed in on a device they
     * have just signed out, and the screen then sends them to /login, which redirects
     * an authenticated caller straight back to the mailbox, so the click would appear
     * to have done nothing at all. Ending the session without closing the mailbox
     * leaves the password sitting in this process, which is the one thing
     * MailboxAccess.close exists to prevent. SecurityContextLogoutHandler is used
     * rather than a hand written invalidate because it is the class LogoutFilter
     * itself uses, and this must not become a second and slightly different definition
     * of signing out.
     *
     * Closing the mailbox can lock a colleague's tab on a shared address, because the
     * credential store is process wide. That is the same cost /logout already accepts
     * for the same reason, and re-entering a password is much the smaller harm.
     */
    private void signOutHere(Authentication auth, HttpSession session, HttpServletRequest request,
                             HttpServletResponse response) {
        mailboxes.close(auth, session);
        DeviceCookie.clear(response, settings.isCookieSecure());
        new SecurityContextLogoutHandler().logout(request, response, auth);
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

    /**
     * One device as the screen reads it, with the names this package uses kept beside
     * the names the screen uses.
     *
     * Both sets are sent on purpose. The client reads name, platform, ip, lastSeenAt,
     * createdAt, current and mailbox; label, lastIp, lastSeen and firstSeen are what
     * this package called the same values before there was a screen, and dropping them
     * would break a curl somebody has in their notes for the sake of four keys. That
     * duplication is the entire cost of serving both vocabularies, and it is far
     * smaller than the cost of having picked one and been wrong, which is what this
     * method is a repair for.
     */
    private static Map<String, Object> render(DeviceSummary device) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", device.id());
        row.put("name", device.label());
        row.put("label", device.label());
        // Null rather than a guess. DeviceLabel already folds the platform and the
        // browser into the one string this package stores, and splitting it back apart
        // here would mean a second copy of that class's platform table living in a
        // controller, which is exactly how two descriptions of one thing drift apart.
        // The screen falls back to the name when this is absent and picks its sprite
        // from the two joined together, so a phone still gets the phone sprite.
        row.put("platform", null);
        row.put("current", device.current());
        row.put("createdAt", iso(device.firstSeen()));
        row.put("firstSeen", iso(device.firstSeen()));
        row.put("lastSeenAt", iso(device.lastSeen()));
        row.put("lastSeen", iso(device.lastSeen()));
        row.put("ip", device.lastIp());
        row.put("lastIp", device.lastIp());
        // True for every row this server can produce, because DeviceTokenService.enrol
        // refuses to write a token without a sealed mailbox credential, so every live
        // device really can reopen the mailbox without the mail password. Sent as a
        // constant rather than left out, since the screen prints its warning only when
        // it reads false, and a missing key would be read as true by accident instead
        // of on purpose.
        row.put("mailbox", true);
        return row;
    }

    /** ISO-8601 strings rather than epoch numbers, so the screen can print them as they arrive. */
    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
