package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.mail.MailFolder;
import com.jarurat.mailer.mail.MailService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one endpoint an open tab is allowed to call on a timer.
 *
 * Everything else under /api/mail is called because a person did something. This
 * one is called every forty-five seconds for as long as a laptop lid stays open,
 * which makes its cost the only thing about it worth arguing over, and the budget
 * it was given is "no dearer than the folder count query the screen already runs".
 * That budget is met by construction rather than by hope:
 *
 *   - The unread count is read from Mailbox/get's unreadEmails, which is exactly
 *     what GET /api/mail/folders reads and is a counter Stalwart maintains rather
 *     than a search it has to run. This call asks for four properties where that
 *     one asks for seven.
 *   - The newest message costs an Email/query capped at limit 1 and an Email/get
 *     of that single id, and both ride in the same JMAP request as the Mailbox/get
 *     through a back reference. So a poll is ONE round trip to Stalwart, the same
 *     as a folder count, and the round trip is the expensive part; see the note at
 *     the top of MailService.
 *
 * Being honest about the difference: a poll does strictly more work inside that one
 * round trip than a folder count does, by one bounded query and one single-message
 * fetch. What it does not do is add a second round trip, scan a folder, or fetch a
 * body.
 *
 * There is no way to poll a mailbox other than the one pinned to this browser
 * session, for the same reason no other endpoint here takes a mailbox parameter.
 * See MailboxAccess.
 */
@RestController
@RequestMapping("/api/mail")
public class MailPollApi {

    private static final List<String> MAIL_CAPS = List.of(JmapClient.CORE, JmapClient.MAIL);

    /** Four, not the seven /api/mail/folders asks for: nothing here draws a folder list. */
    private static final List<String> FOLDER_PROPS = List.of("id", "role", "totalEmails", "unreadEmails");

    /** Everything one line of a notification needs, and nothing that costs a body fetch. */
    private static final List<String> NEWEST_PROPS = List.of("id", "receivedAt", "subject", "from", "keywords");

    /** A notification body is one line on a phone; the rest is bytes on the wire every 45 seconds. */
    private static final int SUBJECT_CLIP = 140;

    /** Two at most: the first, and one more after a stale inbox id has been corrected. */
    private static final int ATTEMPTS = 2;

    private final MailboxAccess mailbox;
    private final MailService mail;
    private final JmapClient jmap;

    /**
     * Mailbox address to the JMAP id of that account's inbox.
     *
     * The id has to be known before the request is built, because it goes inside
     * Email/query's filter and a JMAP result reference can only stand in for a whole
     * top-level argument, never for one field buried inside one. Without this cache
     * every poll would be two round trips: one to learn the id and one to use it. It
     * holds nothing secret, only an opaque per-account id, and it is checked against
     * the Mailbox/get that rides along in the same request, so a stale entry survives
     * exactly one call.
     */
    private final Map<String, String> inboxIds = new ConcurrentHashMap<>();

    public MailPollApi(MailboxAccess mailbox, MailService mail, JmapClient jmap) {
        this.mailbox = mailbox;
        this.mail = mail;
        this.jmap = jmap;
    }

    /**
     * What changed in the inbox, in as few bytes as the question allows.
     *
     * newest is the newest message in the inbox whether or not it has been read, and
     * it carries its own seen flag, rather than being the newest UNREAD message. That
     * choice is what keeps the client from crying wolf: reading the top message
     * changes which message is newest-and-unread, so a client watching that field
     * would announce the one underneath it as an arrival. The newest message only
     * changes when something actually arrives.
     */
    @GetMapping("/poll")
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public Map<String, Object> poll(Authentication auth, HttpSession session) {
        String user = mailbox.require(auth, session);

        String inbox = inboxIds.get(user);
        if (inbox == null) {
            // The cold path, once per mailbox per process. It reuses the folder listing
            // the rest of the app already goes through rather than hand-rolling a
            // fourth Mailbox/get: it costs one extra round trip on the very first poll
            // and nothing on any poll after it.
            MailFolder found = mail.folderByRole(user, "inbox");
            if (found == null) return noInbox(user);
            inbox = found.id();
            inboxIds.put(user, inbox);
        }

        MailException failure = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                Map<String, Object> answer = snapshot(user, inbox);
                if (answer != null) return answer;
            } catch (MailException e) {
                // A filter naming a mailbox id that no longer exists is the one failure a
                // retry can fix, and it is not distinguishable here from any other method
                // error, so the retry is gated on the id having actually moved rather than
                // on the error type.
                failure = e;
            }
            String fresh = freshInboxId(user);
            if (fresh == null || fresh.equals(inbox)) break;
            inboxIds.put(user, fresh);
            inbox = fresh;
        }
        if (failure != null) throw failure;
        return noInbox(user);
    }

    /**
     * One JMAP request carrying the three calls, or null when the Mailbox/get that
     * came back names a different inbox than the one Email/query was filtered on.
     *
     * Null rather than an exception, because that is not a failure: the account was
     * rebuilt, this poll asked about a folder that is gone, and the caller has just
     * been handed the right id to ask again with.
     */
    private Map<String, Object> snapshot(String user, String inboxId) {
        ObjectNode boxes = jmap.accountArgs(user);
        boxes.putNull("ids");
        ArrayNode boxProps = boxes.putArray("properties");
        for (String p : FOLDER_PROPS) boxProps.add(p);

        ObjectNode query = jmap.accountArgs(user);
        query.putObject("filter").put("inMailbox", inboxId);
        query.putArray("sort").addObject().put("property", "receivedAt").put("isAscending", false);
        query.put("position", 0);
        query.put("limit", 1);
        // The unread count comes off the mailbox counter above, so a total would add
        // nothing here and calculating one is work Stalwart is allowed to skip.
        query.put("calculateTotal", false);

        // Back reference, the same trick MailService.page uses: Email/get reads the one
        // id Email/query just produced, so the pair costs no extra round trip.
        ObjectNode get = jmap.accountArgs(user);
        ObjectNode ref = get.putObject("#ids");
        ref.put("resultOf", "q0");
        ref.put("name", "Email/query");
        ref.put("path", "/ids");
        ArrayNode mailProps = get.putArray("properties");
        for (String p : NEWEST_PROPS) mailProps.add(p);

        JsonNode responses = jmap.call(user, MAIL_CAPS, jmap.newArray()
                .add(jmap.invocation("Mailbox/get", boxes, "p0"))
                .add(jmap.invocation("Email/query", query, "q0"))
                .add(jmap.invocation("Email/get", get, "g0")));

        JsonNode folders = jmap.response(responses, "Mailbox/get", "p0");
        JsonNode inbox = null;
        for (JsonNode f : folders.path("list")) {
            if ("inbox".equalsIgnoreCase(JmapClient.text(f, "role"))) {
                inbox = f;
                break;
            }
        }
        if (inbox == null) return noInbox(user);

        String liveId = JmapClient.text(inbox, "id");
        if (liveId != null && !liveId.equals(inboxId)) {
            inboxIds.put(user, liveId);
            return null;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mailbox", user);
        out.put("folderId", inboxId);
        out.put("unread", inbox.path("unreadEmails").asInt(0));
        out.put("total", inbox.path("totalEmails").asInt(0));
        out.put("newest", newest(jmap.response(responses, "Email/get", "g0")));
        return out;
    }

    /**
     * The one row Email/get returned, flattened, or null for an empty inbox.
     *
     * from is a display string and not the {name, email, display} object the rest of
     * this API hands over, because the only thing that ever reads it is the single
     * line of a desktop notification. The object would be three times the bytes on a
     * request that repeats every forty-five seconds for hours.
     */
    private static Map<String, Object> newest(JsonNode fetched) {
        JsonNode e = fetched.path("list").path(0);
        if (e.isMissingNode() || e.isNull()) return null;

        JsonNode sender = e.path("from").path(0);
        String name = JmapClient.text(sender, "name");
        String email = JmapClient.text(sender, "email");
        // The same fallback MailAddress.display() makes, so a sender with no display
        // name reads as their address here exactly as it does in the reading pane.
        String from = name == null || name.isBlank() ? nz(email) : name;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", nz(JmapClient.text(e, "id")));
        row.put("receivedAt", nz(JmapClient.text(e, "receivedAt")));
        row.put("subject", clip(nz(JmapClient.text(e, "subject"))));
        row.put("from", from);
        row.put("seen", e.path("keywords").path("$seen").asBoolean(false));
        return row;
    }

    /**
     * An account with no inbox is not an error and must not stop the client's timer,
     * so it answers zero and no newest message, which is the truth.
     */
    private static Map<String, Object> noInbox(String user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mailbox", user);
        out.put("folderId", "");
        out.put("unread", 0);
        out.put("total", 0);
        out.put("newest", null);
        return out;
    }

    /** Re-reads the inbox id from the server, or null when there is no inbox to read. */
    private String freshInboxId(String user) {
        try {
            MailFolder found = mail.folderByRole(user, "inbox");
            return found == null ? null : found.id();
        } catch (MailException e) {
            return null;
        }
    }

    private static String clip(String s) {
        return s.length() <= SUBJECT_CLIP ? s : s.substring(0, SUBJECT_CLIP - 3) + "...";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ failures

    /*
     * The handlers below are deliberate copies of MailApiController's rather than a
     * shared @ControllerAdvice. An @ExceptionHandler declared on a controller covers
     * only that controller, so without these a locked mailbox would leave this
     * endpoint answering 500 with a stack trace; and advice broad enough to cover
     * both classes would also start answering for every other controller in the
     * application. Two small copies are cheaper than either.
     */

    /**
     * 409 and not 401, and the client's whole stop rule hangs off the difference: 409
     * means the console session is fine and only the mailbox is shut, so the timer
     * stops and waits for a person, while 401 means the session has gone and the
     * timer must never start again on this page.
     */
    @ExceptionHandler(MailboxAccess.MailboxLockedException.class)
    public ResponseEntity<?> onLocked(MailboxAccess.MailboxLockedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage(), "locked", true));
    }

    @ExceptionHandler(MailException.class)
    public ResponseEntity<?> onMailFailure(MailException e) {
        String message = e.getMessage() == null ? "The mail server refused that." : e.getMessage();
        return switch (e.getKind()) {
            case AUTH -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", message, "locked", true));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", message));
            case PROTOCOL -> ResponseEntity.badRequest().body(Map.of("error", message));
            default -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "The mail server did not answer: " + message));
        };
    }

    /**
     * The rethrow is not optional, for the reason spelled out on the same handler in
     * MailApiController: a @PreAuthorize denial arrives here as a RuntimeException,
     * and swallowing it would turn every 403 into a 502 and hand a caller without
     * MAIL_READ a soft failure instead of a refusal.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> onFailure(RuntimeException e) {
        if (e instanceof AccessDeniedException) throw e;
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "The mail poll hit an unexpected fault: "
                        + e.getClass().getSimpleName()));
    }
}
