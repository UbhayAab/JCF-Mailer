package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.Attachment;
import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.mail.MailService;
import com.jarurat.mailer.mail.MessageBody;
import com.jarurat.mailer.mail.OutboundHtml;
import com.jarurat.mailer.mail.Outgoing;
import com.jarurat.mailer.models.QueuedMessage;
import com.jarurat.mailer.services.SesSender;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.TypeMismatchException;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The outbox: send in a few seconds so it can be taken back, or send on Monday.
 *
 * This is a second send endpoint rather than a flag on the first one, and that is a
 * deliberate choice about failure. POST /api/mail/send answers once the mail server
 * has accepted the message, so its 200 means it has gone. This one answers as soon as
 * the message is safely in Postgres, so its 200 means only that we have promised to
 * send it. Those are different promises and a screen has to say different things
 * about them, so they are different URLs; a caller cannot accidentally get the weaker
 * one. Everything a compose sheet already posts to /send works here unchanged, with
 * one extra optional parameter.
 *
 * Undo is a delay and not a recall, and every sentence here is written on that basis.
 * While the message is held it can be stopped outright and nothing has left this
 * building. Once the window has closed there is no control offered at all, because
 * mail cannot be pulled back out of somebody else's server and a button that pretends
 * otherwise is a lie a person only discovers at the moment it matters most.
 *
 * The mailbox comes from the session through MailboxAccess exactly as it does
 * everywhere else in this package, and a queued message carries the mailbox that
 * created it on the row. Every read and every state change names that mailbox in the
 * SQL, so one mailbox cannot see, cancel or edit another one's outbox even though the
 * rows outlive the browser session that made them.
 */
@RestController
@RequestMapping("/api/mail/outbox")
public class OutboxApi {

    /** Same ceiling as the immediate send path, for the same reason: parts are round trips. */
    private static final int MAX_FILES = 20;

    private static final Pattern MIME_TYPE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,62}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,62}");

    private static final int MAX_ADDRESS_FIELD = 4000;

    private final OutboxService outbox;
    private final MailService mail;
    private final MailboxAccess mailbox;
    private final JmapClient jmap;

    public OutboxApi(OutboxService outbox, MailService mail, MailboxAccess mailbox, JmapClient jmap) {
        this.outbox = outbox;
        this.mail = mail;
        this.mailbox = mailbox;
        this.jmap = jmap;
    }

    // ------------------------------------------------------------------ queueing

    /**
     * Takes a message and promises to send it, either in a few seconds or at a named
     * time.
     *
     * sendAt is optional and is an instant, not a wall clock reading: send
     * 2026-09-01T09:00:00Z or 2026-09-01T14:30+05:30 and the server converts. A bare
     * local time is accepted too and is read in this server's own zone, which is the
     * one case where a client and a server can disagree about what nine in the morning
     * means, so a client that cares should send an offset.
     *
     * Files are uploaded to the mail server here rather than when the message goes,
     * because the browser that attached them will be closed by then. That is also why
     * a message carrying files cannot be scheduled far ahead; see OutboxService.
     */
    @PostMapping("/send")
    @PreAuthorize("hasAuthority('MAIL_SEND')")
    public ResponseEntity<?> queue(Authentication auth,
                                   HttpSession session,
                                   @RequestParam String to,
                                   @RequestParam(required = false) String cc,
                                   @RequestParam(required = false) String bcc,
                                   @RequestParam(defaultValue = "") String subject,
                                   @RequestParam(defaultValue = "") String body,
                                   @RequestParam(required = false) String html,
                                   @RequestParam(required = false) String replyTo,
                                   @RequestParam(required = false) String sendAt,
                                   @RequestParam(value = "files", required = false) MultipartFile[] files) {
        String user = mailbox.require(auth, session);

        Outgoing message = compose(to, cc, bcc, subject, body, html);
        String parentId = null;
        if (replyTo != null && !replyTo.isBlank()) {
            // The parent is read now rather than at send time, and the two headers are
            // derived from what the mail server says it is rather than from anything
            // the browser claimed. A client that could set In-Reply-To directly could
            // staple our message into the middle of somebody else's conversation.
            parentId = replyTo.trim();
            MessageBody parent = mail.getMessage(user, parentId);
            if (parent.messageId() != null && !parent.messageId().isBlank()) {
                message = message.inThread(parent.messageId(), parent.references());
            }
        }

        List<MultipartFile> chosen = chosenFiles(files);
        String refusal = refuse(chosen);
        if (refusal != null) return ResponseEntity.badRequest().body(Map.of("error", refusal));

        message = message.withAttachments(uploadAll(user, chosen));
        QueuedMessage row = outbox.queue(user, message, when(sendAt), parentId);
        return ResponseEntity.ok(confirmation(row));
    }

    /**
     * Rewrites a message that has not gone yet. Answers with a new id, because what is
     * queued is a snapshot and a new snapshot is a new thing; see OutboxService.
     */
    @PostMapping("/update")
    @PreAuthorize("hasAuthority('MAIL_SEND')")
    public ResponseEntity<?> update(Authentication auth,
                                    HttpSession session,
                                    @RequestParam Long id,
                                    @RequestParam String to,
                                    @RequestParam(required = false) String cc,
                                    @RequestParam(required = false) String bcc,
                                    @RequestParam(defaultValue = "") String subject,
                                    @RequestParam(defaultValue = "") String body,
                                    @RequestParam(required = false) String html,
                                    @RequestParam(required = false) String replyTo,
                                    @RequestParam(required = false) String sendAt) {
        String user = mailbox.require(auth, session);

        Outgoing message = compose(to, cc, bcc, subject, body, html);
        String parentId = null;
        if (replyTo != null && !replyTo.isBlank()) {
            parentId = replyTo.trim();
            MessageBody parent = mail.getMessage(user, parentId);
            if (parent.messageId() != null && !parent.messageId().isBlank()) {
                message = message.inThread(parent.messageId(), parent.references());
            }
        }

        // Files are not carried through an edit. Re-uploading them would double what
        // the mail server is holding, and pointing the new row at the old row's blobs
        // would leave two rows depending on one upload, so an edit that needs different
        // files is a cancel and a fresh send. The screen has to say so.
        OutboxService.Outcome outcome = outbox.replace(user, id, message, when(sendAt), parentId);
        return answer(outcome);
    }

    /**
     * Stops a message that is still held. Refuses, in words, once it is not.
     *
     * The refusal is a 409 rather than a 400 because nothing about the request was
     * wrong: it arrived a moment after the thing it was asking about stopped being
     * possible, and the screen should say that rather than blame the person.
     */
    @PostMapping("/cancel")
    @PreAuthorize("hasAuthority('MAIL_SEND')")
    public ResponseEntity<?> cancel(Authentication auth, HttpSession session, @RequestParam Long id) {
        String user = mailbox.require(auth, session);
        return answer(outbox.cancel(user, id));
    }

    /** Marks a failure as read, so it stops being shown in the outbox. */
    @PostMapping("/dismiss")
    @PreAuthorize("hasAuthority('MAIL_SEND')")
    public ResponseEntity<?> dismiss(Authentication auth, HttpSession session, @RequestParam Long id) {
        String user = mailbox.require(auth, session);
        return answer(outbox.acknowledge(user, id));
    }

    // ------------------------------------------------------------------ reading

    /**
     * What is still waiting, and what failed while nobody was looking.
     *
     * The failures are the reason this is a listing rather than only a toast on the
     * compose sheet. A scheduled send that fails at three in the morning has nobody to
     * tell, so it stays in this list until somebody dismisses it, and the count is
     * here so the mail screen can put a number on the outbox without a second request.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public Map<String, Object> list(Authentication auth, HttpSession session) {
        String user = mailbox.require(auth, session);
        LocalDateTime now = LocalDateTime.now();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (QueuedMessage row : outbox.open(user)) rows.add(json(row, now));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", rows);
        out.put("failures", outbox.unseenFailures(user));
        out.put("undoSeconds", outbox.undoSeconds());
        out.put("maxDaysAhead", outbox.maxDaysAhead());
        out.put("attachmentHours", outbox.attachmentHours());
        out.put("mailbox", user);
        return out;
    }

    // ------------------------------------------------------------------ shaping

    private Map<String, Object> confirmation(QueuedMessage row) {
        Map<String, Object> out = new LinkedHashMap<>(json(row, LocalDateTime.now()));
        out.put("ok", true);
        out.put("undoSeconds", outbox.undoSeconds());
        out.put("message", QueuedMessage.SCHEDULED.equals(row.getKind())
                ? "Scheduled. It goes at " + iso(row.getSendAt()) + " and can be stopped until then."
                : "Sending in " + outbox.undoSeconds() + " seconds. It can be stopped until then.");
        return out;
    }

    /**
     * One queued message as JSON.
     *
     * cancelUntil is the same value as sendAt and is sent under its own name anyway,
     * because it is the one the screen counts down and the one it must stop offering
     * Undo at. cancellable is computed here rather than left to the client to work out
     * from a clock that may be minutes off this server's.
     */
    private static Map<String, Object> json(QueuedMessage row, LocalDateTime now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("kind", row.getKind());
        out.put("state", row.getState());
        out.put("sendAt", iso(row.getSendAt()));
        out.put("cancelUntil", iso(row.getSendAt()));
        out.put("cancellable", row.cancellableAt(now));
        out.put("queuedAt", iso(row.getQueuedAt()));
        out.put("subject", row.getSubject());
        out.put("to", row.getTo());
        out.put("cc", row.getCc());
        out.put("bcc", row.getBcc());
        out.put("attempts", row.getAttempts());
        out.put("error", row.getLastError() == null ? "" : row.getLastError());
        out.put("sentId", row.getSentEmailId() == null ? "" : row.getSentEmailId());
        out.put("replacedBy", row.getReplacedById());

        List<Map<String, Object>> files = new ArrayList<>();
        for (Attachment file : row.getAttachments()) {
            files.add(Map.of("name", file.safeName(), "size", file.size()));
        }
        out.put("files", files);
        return out;
    }

    private static ResponseEntity<?> answer(OutboxService.Outcome outcome) {
        if (outcome.ok()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("message", outcome.message());
            if (outcome.row() != null) out.putAll(json(outcome.row(), LocalDateTime.now()));
            return ResponseEntity.ok(out);
        }
        HttpStatus status = outcome.problem() == OutboxService.Problem.NOT_FOUND
                ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Map.of("error", outcome.message(), "tooLate",
                outcome.problem() == OutboxService.Problem.TOO_LATE));
    }

    /**
     * The time a message should go, read from what the browser sent.
     *
     * Three shapes are accepted and only one of them is ambiguous. An instant and an
     * offset date time both name a moment on Earth. A bare local date and time does
     * not, and it is read in this server's zone, which is the reading a person sitting
     * in the same country will have meant.
     */
    private static LocalDateTime when(String sendAt) {
        if (sendAt == null || sendAt.isBlank()) return null;
        String typed = sendAt.trim();
        try {
            return LocalDateTime.ofInstant(Instant.parse(typed), ZoneId.systemDefault());
        } catch (DateTimeParseException ignored) {
            // Not an instant. Try the other two before giving up.
        }
        try {
            return OffsetDateTime.parse(typed).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // Not an offset date time either.
        }
        try {
            return LocalDateTime.parse(typed);
        } catch (DateTimeParseException e) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "That is not a time this server can read. Send it as 2026-09-01T09:00:00Z.");
        }
    }

    private static String iso(LocalDateTime when) {
        return when == null ? "" : when.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    // ------------------------------------------------------------------ composing

    /**
     * The compose sheet's fields turned into one message.
     *
     * This repeats the parsing MailApiController does privately, and the repetition is
     * knowing rather than accidental. Making it shared would mean editing a file this
     * agent does not own while another is working in it, and the rules themselves are
     * not duplicated: the address form is SesSender.EMAIL_OK, the normalising is
     * Outgoing.normalise, the recipient ceiling is Outgoing.MAX_RECIPIENTS and the
     * HTML ceiling is OutboundHtml.MAX_HTML, all of them the one implementation. What
     * is repeated is the loop around them. Whoever owns both files next should lift
     * this into one place.
     */
    private static Outgoing compose(String to, String cc, String bcc,
                                    String subject, String body, String html) {
        if (html != null && html.length() > OutboundHtml.MAX_HTML) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "That message is too long to send as formatted mail. Gmail hides anything past "
                            + "about 100KB behind a link, so trim the quoted history and try again.");
        }
        // A caller that sends no html gets the same HTML part the immediate send path
        // builds for it. Letting this one fall through to a text-only message instead
        // would mean the same letter arrived formatted or unformatted depending on
        // whether the sender chose to schedule it, which is not a choice they made.
        String markup = html == null || html.isBlank() ? composeHtml(body) : html;
        return new Outgoing(
                parseRecipients("To", to), parseRecipients("Cc", cc), parseRecipients("Bcc", bcc),
                subject == null ? "" : subject.trim(), markup, body, List.of(), null, List.of());
    }

    private static List<String> parseRecipients(String label, String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        if (raw.length() > MAX_ADDRESS_FIELD) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    label + " is longer than this form accepts. Send a list that size from Campaign Studio.");
        }
        for (String part : raw.split("[,;\n]")) {
            String typed = part.trim();
            if (typed.isEmpty()) continue;
            String address = Outgoing.normalise(typed);
            if (address == null || !SesSender.EMAIL_OK.matcher(address).matches()) {
                throw new MailException(MailException.Kind.PROTOCOL,
                        label + ": \"" + clip(typed) + "\" is not an email address.");
            }
            boolean already = false;
            for (String seen : out) {
                if (seen.equalsIgnoreCase(address)) {
                    already = true;
                    break;
                }
            }
            if (!already) out.add(address);
        }
        return out;
    }

    /** Typed text to a plain HTML part: escaped, blank lines become paragraphs. */
    private static String composeHtml(String text) {
        if (text == null || text.isBlank()) return "<p></p>";
        StringBuilder out = new StringBuilder(text.length() + 64);
        for (String block : text.replace("\r\n", "\n").split("\n\\s*\n")) {
            if (block.isBlank()) continue;
            out.append("<p>");
            String[] lines = block.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) out.append("<br>");
                out.append(escape(lines[i]));
            }
            out.append("</p>");
        }
        return out.length() == 0 ? "<p></p>" : out.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static List<MultipartFile> chosenFiles(MultipartFile[] files) {
        List<MultipartFile> out = new ArrayList<>();
        if (files == null) return out;
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) out.add(file);
        }
        return out;
    }

    /** The sentence to answer with instead of queueing, or null when the files are fine. */
    private String refuse(List<MultipartFile> files) {
        if (files.isEmpty()) return null;
        if (files.size() > MAX_FILES) {
            return "That is " + files.size() + " files on one message. Send at most " + MAX_FILES
                    + ", or put them in a shared folder and send the link.";
        }

        long total = 0;
        for (MultipartFile file : files) {
            String refused = Attachment.refusedExtension(file.getOriginalFilename());
            if (refused == null) refused = Attachment.refusedExtension(displayName(file));
            if (refused != null) {
                return displayName(file) + " was not queued. A ." + refused
                        + " file runs as a program the moment it is opened, so this mailer will not"
                        + " carry one and no serious mail server on the other end would accept it."
                        + " Put it in a shared folder and send the link instead.";
            }
            total += Math.max(0L, file.getSize());
        }

        long cap = mail.maxAttachmentBytes();
        if (total > cap) {
            return "Those files come to " + mb(total) + ", which is about "
                    + mb(MailService.encodedSize(total)) + " once encoded for email, over the "
                    + mb(cap) + " a message can carry. Remove one, or send a link instead.";
        }
        return null;
    }

    private List<Attachment> uploadAll(String user, List<MultipartFile> files) {
        List<Attachment> out = new ArrayList<>();
        for (MultipartFile file : files) {
            Supplier<InputStream> source = () -> {
                try {
                    return file.getInputStream();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
            JmapClient.Blob blob = jmap.upload(user, safeType(file.getContentType()), file.getSize(), source);
            out.add(Attachment.outgoing(blob.blobId(), displayName(file), blob.type(), blob.size()));
        }
        return out;
    }

    private static String safeType(String declared) {
        if (declared == null) return "application/octet-stream";
        String type = declared.trim();
        int semi = type.indexOf(';');
        if (semi >= 0) type = type.substring(0, semi).trim();
        return MIME_TYPE.matcher(type).matches()
                ? type.toLowerCase(Locale.ROOT)
                : "application/octet-stream";
    }

    private static String displayName(MultipartFile file) {
        return new Attachment(null, null, file.getOriginalFilename(), null, 0L, null, null).safeName();
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576d);
    }

    private static String clip(String value) {
        return value.length() <= 240 ? value : value.substring(0, 237) + "...";
    }

    // ------------------------------------------------------------------ failures

    /**
     * The same four handlers the immediate send path has, because an @ExceptionHandler
     * belongs to the controller that declares it and a caller must not get a stack
     * trace here for the request that gets a sentence there.
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

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<?> onBadParam(TypeMismatchException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Bad request parameter."));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> onUploadTooBig(MultipartException e) {
        String message = e instanceof MaxUploadSizeExceededException
                ? "That upload is bigger than this server will take in one request. "
                        + "Attach less than " + mb(mail.maxAttachmentBytes()) + " of files."
                : "That upload did not arrive in one piece. Nothing was queued. Attach the files again.";
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of("error", message));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<?> onUploadUnreadable(UncheckedIOException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "An attached file could not be read back off this server. "
                        + "Nothing was queued. Attach it again."));
    }

    /**
     * The rethrow is not optional. A @PreAuthorize denial arrives here as an
     * AccessDeniedException, and a handler this broad would otherwise turn every 403
     * into a 502.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> onFailure(RuntimeException e) {
        if (e instanceof AccessDeniedException) throw e;
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "The outbox hit an unexpected fault: "
                        + e.getClass().getSimpleName()));
    }
}
