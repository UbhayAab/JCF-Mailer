package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.mail.OutboundHtml;
import com.jarurat.mailer.models.MailboxSettings;
import com.jarurat.mailer.repositories.MailboxSettingsRepository;
import com.jarurat.mailer.services.AuditService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-mailbox preferences: a signature, an out of office reply, and how the screen
 * reads and sends.
 *
 * WHAT IS STORED HERE AND WHAT IS NOT
 * -----------------------------------
 * The rule the build plan holds to is that anything a second mail client could also
 * change belongs to Stalwart and never to Postgres, because a second copy disagrees
 * with the first the day somebody opens the account in Thunderbird. Reading and
 * sending preferences pass that test trivially: they are how this application draws
 * its own screen and nothing on the mail server has an opinion about them. The out of
 * office fails it, and so it is the one setting this class pushes: what is in the row
 * is the request, and every save tries to make Stalwart the thing that actually
 * answers, because an out of office that only fires while somebody has a browser tab
 * open is not an out of office.
 *
 * The signature is the uncomfortable one. It would properly live on the JMAP Identity,
 * where the desktop clients some staff also use would see the same one, and
 * Identity/set is one method call away. It is here instead for a reason that is about
 * ownership rather than design: this class does not own MailService, another agent is
 * in that tree, and the composer that has to append the signature is a third file
 * again. Storing it here makes the feature real today and leaves exactly one migration
 * to do later, which is to push it to Identity on save the way the vacation response
 * is pushed. That is stated in the report rather than hidden here.
 *
 * THE MAILBOX IS NEVER A PARAMETER. Every method resolves it through MailboxAccess
 * from the session pin, so there is no settings row a signed-in user can read or write
 * except the one belonging to the mailbox they produced a password for.
 */
@RestController
@RequestMapping("/api/mail/settings")
public class MailSettingsApi {

    /**
     * The capability that decides whether the out of office is real.
     *
     * RFC 8621 defines a VacationResponse singleton behind this URN, with isEnabled,
     * fromDate, toDate, subject, textBody and htmlBody, and the mail server keeps its
     * own once-per-sender ledger underneath it. Whether the deployed build advertises it
     * was phase 0 of the build plan and nobody has run phase 0, so this class does not
     * ask the session document, it simply tries the call and records what came back. A
     * capability probe that has to be believed is worth less than an attempt that has to
     * succeed.
     */
    static final String VACATION = "urn:ietf:params:jmap:vacationresponse";

    /** RFC 8621 makes it a singleton, and singleton is the id it is filed under. */
    private static final String SINGLETON = "singleton";

    /** JMAP UTCDate: seconds, always Z, never an offset. */
    private static final DateTimeFormatter UTC_DATE = DateTimeFormatter.ISO_INSTANT;

    /**
     * A signature longer than this is a letterhead somebody pasted, and it rides on
     * every message they will ever send. Well under OutboundHtml.MAX_HTML on purpose:
     * the signature is only part of the message and the rest of it still has to fit.
     */
    private static final int MAX_SIGNATURE = 8000;

    private final MailboxSettingsRepository settings;
    private final MailboxAccess mailbox;
    private final JmapClient jmap;
    private final AuditService audit;

    public MailSettingsApi(MailboxSettingsRepository settings, MailboxAccess mailbox,
                           JmapClient jmap, AuditService audit) {
        this.settings = settings;
        this.mailbox = mailbox;
        this.jmap = jmap;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ read

    /**
     * Everything the settings sheet and the composer need, in one call.
     *
     * Deliberately does not talk to the mail server. The last push already recorded
     * whether it landed, so reporting that stored answer means the settings screen still
     * opens and still saves while Stalwart is unreachable, and a person can at least fix
     * their signature during an outage. The cost is that the screen shows the state as
     * of the last save rather than as of now, which for a value only this screen ever
     * writes is not a real difference.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public Map<String, Object> read(Authentication auth, HttpSession session) {
        String address = mailbox.require(auth, session);
        return asJson(load(address), Instant.now());
    }

    // ------------------------------------------------------------------ write

    /**
     * Saves the form and then tries to make the out of office real on the mail server.
     *
     * The order matters. The row is written first so that a mail server that is down or
     * that has no vacation capability still leaves the person with their settings saved,
     * and the push result is then folded into the same row. Writing the row only on a
     * successful push would mean an outage silently discards a signature somebody spent
     * five minutes on.
     *
     * Every parameter is optional and an absent one leaves the stored value alone, so
     * this endpoint is safe to call with one field from a sheet that only changed one
     * field. That is also why nothing here reads a whole object off the request body:
     * absent and empty have to be different, and a JSON body of a bean with primitive
     * booleans cannot express the difference.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public Map<String, Object> save(Authentication auth, HttpSession session,
                                    @RequestParam(required = false) String signatureHtml,
                                    @RequestParam(required = false) String signatureOnNew,
                                    @RequestParam(required = false) String signatureOnReply,
                                    @RequestParam(required = false) String vacationEnabled,
                                    @RequestParam(required = false) String vacationSubject,
                                    @RequestParam(required = false) String vacationHtml,
                                    @RequestParam(required = false) String vacationFrom,
                                    @RequestParam(required = false) String vacationTo,
                                    @RequestParam(required = false) String vacationPeriodDays,
                                    @RequestParam(required = false) String preferHtml,
                                    @RequestParam(required = false) String loadRemoteImages,
                                    @RequestParam(required = false) String messagesPerPage,
                                    @RequestParam(required = false) String readingPane,
                                    @RequestParam(required = false) String undoSendSeconds,
                                    @RequestParam(required = false) String defaultReply,
                                    @RequestParam(required = false) String requestReadReceipt) {

        String address = mailbox.require(auth, session);
        Instant now = Instant.now();
        MailboxSettings row = load(address);
        boolean wasAnswering = row.vacationActive(now);

        if (signatureHtml != null) row.setSignatureHtml(cleanSignature(signatureHtml, "signature"));
        if (signatureOnNew != null) row.setSignatureOnNew(flag(signatureOnNew));
        if (signatureOnReply != null) row.setSignatureOnReply(flag(signatureOnReply));

        if (vacationEnabled != null) row.setVacationEnabled(flag(vacationEnabled));
        if (vacationSubject != null) row.setVacationSubject(vacationSubject);
        if (vacationHtml != null) {
            row.setVacationHtml(cleanSignature(vacationHtml, "out of office message"));
        }
        if (vacationFrom != null) row.setVacationFrom(instantOrNull(vacationFrom, "start date"));
        if (vacationTo != null) row.setVacationTo(instantOrNull(vacationTo, "end date"));
        if (vacationPeriodDays != null) row.setVacationPeriodDays(number(vacationPeriodDays, 7));

        if (preferHtml != null) row.setPreferHtml(flag(preferHtml));
        if (loadRemoteImages != null) row.setLoadRemoteImages(flag(loadRemoteImages));
        if (messagesPerPage != null) row.setMessagesPerPage(number(messagesPerPage, 50));
        if (readingPane != null) row.setReadingPane(readingPane);

        if (undoSendSeconds != null) row.setUndoSendSeconds(number(undoSendSeconds, 10));
        if (defaultReply != null) row.setDefaultReply(defaultReply);
        if (requestReadReceipt != null) row.setRequestReadReceipt(flag(requestReadReceipt));

        if (row.getVacationFrom() != null && row.getVacationTo() != null
                && row.getVacationTo().isBefore(row.getVacationFrom())) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "The out of office ends before it starts. Check the two dates.");
        }
        if (row.isVacationEnabled() && row.getVacationHtml().isBlank()) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "Write the out of office message before switching it on. "
                            + "A blank automatic reply is worse than none.");
        }

        // A fresh absence starts everybody period over. Somebody who wrote in during the
        // last trip and was answered then is a different correspondent this time, and
        // carrying the old ledger forward would silently drop the first reply of the new
        // absence for exactly the people who write most often.
        if (row.isVacationEnabled() && !wasAnswering) row.clearAutoReplyLog();
        row.forgetExpired(now);
        row.setUpdatedAt(now);

        Sync sync = pushVacation(address, row, now);
        row.setVacationSync(sync.serverSide(), sync.note(), now);

        MailboxSettings saved = settings.save(row);

        audit.record("MAIL_SETTINGS_SAVED", address,
                "signature " + (saved.getSignatureHtml().isBlank() ? "cleared" : "set")
                        + ", out of office " + (saved.isVacationEnabled() ? "on" : "off")
                        + ", answering at " + (saved.isVacationServerSide() ? "the mail server" : "this app")
                        + ", one reply per sender per " + saved.getVacationPeriodDays() + " days");

        return asJson(saved, now);
    }

    // ------------------------------------------------------------------
    // The out of office, pushed to the mail server
    // ------------------------------------------------------------------

    /** What one attempt at VacationResponse/set came back with. */
    record Sync(boolean serverSide, String note) {
    }

    /**
     * Hands the out of office to Stalwart, and answers honestly when it will not take it.
     *
     * This is the server side route the brief asks for, and it is preferred for one
     * reason that has nothing to do with elegance: mail arrives at three in the morning
     * while the person it is addressed to is on a train to a camp with no browser open
     * anywhere, and only the mail server is awake to answer it. An application side
     * responder would need this process running, a poller watching the inbox and a
     * scheduler, and it would still miss everything that arrived while the box was being
     * deployed.
     *
     * RFC 8621 VacationResponse is used rather than a generated Sieve script, even though
     * Stalwart lists RFC 9661 and RFC 5230 and Sieve vacation has the one thing this
     * object lacks, which is an explicit :days for the once-per-sender period. The
     * trade is deliberate: VacationResponse is one method call with a date range built
     * in, and SieveScript/set means generating a script, guarding the date range with
     * currentdate tests, activating it, and never being able to read a hand-edited script
     * back into the form. Doing that blind against a server nobody in this phase can
     * reach would be two untested integrations instead of one. The period stored in the
     * row is therefore advisory whenever the server is the one answering: the mail server
     * keeps its own ledger and RFC 5230 makes seven days the default it will use.
     *
     * A failure here is never propagated. Every reason this call can fail is a property
     * of the mail server rather than of the form, and taking a signature down with it
     * would be the wrong trade.
     */
    private Sync pushVacation(String user, MailboxSettings row, Instant now) {
        try {
            ObjectNode patch = jmap.newObject();
            patch.put("isEnabled", row.isVacationEnabled());
            patch.put("subject", row.getVacationSubject().isBlank()
                    ? "Out of office" : row.getVacationSubject());
            patch.put("htmlBody", row.getVacationHtml());
            // The text alternative is built from the same HTML rather than stored
            // separately, so the two can never drift apart and nobody has to write the
            // message twice.
            patch.put("textBody", OutboundHtml.toText(row.getVacationHtml()));
            putDate(patch, "fromDate", row.getVacationFrom());
            putDate(patch, "toDate", row.getVacationTo());

            ObjectNode update = jmap.newObject();
            update.set(SINGLETON, patch);
            ObjectNode args = jmap.accountArgs(user);
            args.set("update", update);

            ArrayNode calls = jmap.newArray();
            calls.add(jmap.invocation("VacationResponse/set", args, "v0"));
            JsonNode responses = jmap.call(user, List.of(JmapClient.CORE, VACATION), calls);

            JsonNode result = jmap.response(responses, "VacationResponse/set", "v0");
            JsonNode refused = result.path("notUpdated").path(SINGLETON);
            if (!refused.isMissingNode() && !refused.isNull()) {
                return new Sync(false, "The mail server refused the out of office: "
                        + JmapClient.text(refused, "type") + ".");
            }
            return new Sync(true, row.isVacationEnabled()
                    ? "The mail server is answering this mailbox."
                    : "The mail server has been told to stop answering.");
        } catch (MailException e) {
            // The interesting case, and the one phase 0 was supposed to settle: a build
            // that does not advertise the capability answers unknownCapability and every
            // reply after that is this app failing to be a mail server.
            return new Sync(false, "Saved here, but the mail server did not take it: "
                    + e.getMessage()
                    + " Automatic replies will not be sent while this says so.");
        } catch (RuntimeException e) {
            return new Sync(false, "Saved here, but the mail server could not be reached ("
                    + e.getClass().getSimpleName() + "). Automatic replies will not be sent.");
        }
    }

    private void putDate(ObjectNode target, String field, Instant when) {
        if (when == null) {
            target.putNull(field);
        } else {
            target.put(field, UTC_DATE.format(when.truncatedTo(ChronoUnit.SECONDS)));
        }
    }

    // ------------------------------------------------------------------ shapes

    /**
     * The row as the screen sees it, plus the two things it would otherwise have to work
     * out for itself.
     *
     * signatureForNew and signatureForReply are assembled here rather than in the
     * composer because the separator is a wire format and not a decoration. A line of
     * exactly two hyphens and a space is what tells Gmail, Thunderbird and Outlook where
     * the signature starts, which is how they collapse it out of a quoted reply, and
     * leaving that to a browser script means it is one whitespace edit away from being
     * lost. Sending the finished block also means the composer appends one string and
     * never has to know which of the two switches applied.
     */
    private Map<String, Object> asJson(MailboxSettings row, Instant now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mailbox", row.getMailbox());

        out.put("signatureHtml", row.getSignatureHtml());
        out.put("signatureOnNew", row.isSignatureOnNew());
        out.put("signatureOnReply", row.isSignatureOnReply());
        out.put("signatureForNew", row.isSignatureOnNew() ? signatureBlock(row) : "");
        out.put("signatureForReply", row.isSignatureOnReply() ? signatureBlock(row) : "");

        out.put("vacationEnabled", row.isVacationEnabled());
        out.put("vacationSubject", row.getVacationSubject());
        out.put("vacationHtml", row.getVacationHtml());
        out.put("vacationFrom", iso(row.getVacationFrom()));
        out.put("vacationTo", iso(row.getVacationTo()));
        out.put("vacationPeriodDays", row.getVacationPeriodDays());
        out.put("vacationActive", row.vacationActive(now));
        out.put("vacationServerSide", row.isVacationServerSide());
        out.put("vacationServerNote", row.getVacationServerNote());
        out.put("vacationSyncedAt", iso(row.getVacationSyncedAt()));
        out.put("vacationRepliedSenders", row.autoRepliedCount());

        out.put("preferHtml", row.isPreferHtml());
        out.put("loadRemoteImages", row.isLoadRemoteImages());
        out.put("messagesPerPage", row.getMessagesPerPage());
        out.put("readingPane", row.getReadingPane());

        out.put("undoSendSeconds", row.getUndoSendSeconds());
        out.put("defaultReply", row.getDefaultReply());
        out.put("requestReadReceipt", row.isRequestReadReceipt());

        // Both of these are stored preferences with no consumer on the send path yet, and
        // the screen has to be able to say so rather than promise a delay that will not
        // happen. undoSend needs EmailSubmission sendAt, which is gated on the phase 0
        // capability check; readReceipt needs a Disposition-Notification-To header that
        // MailService does not write.
        out.put("undoSendHonoured", false);
        out.put("readReceiptHonoured", false);

        out.put("updatedAt", iso(row.getUpdatedAt()));
        return out;
    }

    /**
     * The signature with its separator, or nothing at all when it is blank.
     *
     * The separator is written as its own block element rather than as a bare line
     * because OutboundHtml keeps div and drops nearly every attribute, so this is the
     * shape that survives the outbound rebuild intact. The trailing space after the two
     * hyphens is the part of the convention that clients actually match on, and it is
     * written as a numeric entity because an HTML parser collapses a literal trailing
     * space and the entity is what makes it back out of the text alternative.
     */
    private String signatureBlock(MailboxSettings row) {
        String body = row.getSignatureHtml();
        if (body.isBlank()) return "";
        return "<div>--&#32;</div>" + body;
    }

    private MailboxSettings load(String address) {
        return settings.findById(MailboxSettings.normaliseAddress(address))
                .orElseGet(() -> new MailboxSettings(address));
    }

    /**
     * Signature and out of office HTML go through the outbound allowlist, which is the
     * same rebuild an outgoing message goes through and not a second, gentler one.
     *
     * This is a field a person types into a browser and it ends up in front of donors
     * and hospitals, so it is exactly as much attacker-controlled markup as a pasted
     * message body is, and the shared mailbox makes the attacker a colleague rather
     * than a stranger. Cleaning at the boundary rather than on the way out means the
     * stored value is already safe, so nothing downstream has to remember to do it.
     */
    private String cleanSignature(String raw, String what) {
        if (raw == null || raw.isBlank()) return "";
        if (raw.length() > MAX_SIGNATURE) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "That " + what + " is " + raw.length() + " characters of HTML and the limit is "
                            + MAX_SIGNATURE + ". It rides on every message, so it has to be short.");
        }
        return OutboundHtml.clean(raw);
    }

    /**
     * Checkbox truth. Accepts what a form and what fetch each send, because the two
     * disagree: an HTML checkbox posts the literal string on, and a script that
     * serialises a boolean posts true.
     */
    private static boolean flag(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("on") || v.equals("1") || v.equals("yes");
    }

    /** Out of range is clamped by the entity; unparseable falls back rather than 400s. */
    private static int number(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * An instant, epoch milliseconds, or blank for none.
     *
     * Two formats because the browser has two natural ones and neither is wrong. A date
     * is refused rather than guessed at: a wrong out of office start is an absence
     * nobody is told about, and silently reading a malformed date as now would switch
     * the responder on a week early.
     */
    private static Instant instantOrNull(String value, String what) {
        String v = value.trim();
        if (v.isEmpty()) return null;
        try {
            if (v.chars().allMatch(Character::isDigit)) return Instant.ofEpochMilli(Long.parseLong(v));
            return Instant.parse(v);
        } catch (RuntimeException e) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "The " + what + " was not a date this server understands.");
        }
    }

    private static String iso(Instant when) {
        return when == null ? "" : UTC_DATE.format(when.truncatedTo(ChronoUnit.SECONDS));
    }

    // ------------------------------------------------------------------ failures
    //
    // The same shapes MailApiController answers with, because the settings sheet is
    // drawn by the same script that draws the rest of the screen and a second error
    // vocabulary would mean a second set of branches in it.

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
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", message));
            case PROTOCOL -> ResponseEntity.badRequest().body(Map.of("error", message));
            default -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", message));
        };
    }

    /**
     * Last resort. The rethrow is not optional: a PreAuthorize denial arrives here as an
     * AccessDeniedException, and swallowing it would turn a 403 into a 502 and hide the
     * fact that authorisation did its job.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> onFailure(RuntimeException e) {
        if (e instanceof AccessDeniedException) throw e;
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Mail settings hit an unexpected fault: "
                        + e.getClass().getSimpleName()));
    }
}
