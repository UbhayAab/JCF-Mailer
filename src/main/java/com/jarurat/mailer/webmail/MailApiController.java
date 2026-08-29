package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.Attachment;
import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailAddress;
import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.mail.MailFolder;
import com.jarurat.mailer.mail.MailService;
import com.jarurat.mailer.mail.MessageBody;
import com.jarurat.mailer.mail.MessagePage;
import com.jarurat.mailer.mail.MessageSummary;
import com.jarurat.mailer.messagelog.MessageLogService;
import com.jarurat.mailer.security.LoginAddress;
import com.jarurat.mailer.security.LoginRateLimiter;
import com.jarurat.mailer.services.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * JSON behind the webmail screen. Every method resolves the mailbox from the
 * browser session through MailboxAccess and never from a request parameter, so
 * there is no mailbox id for a caller to tamper with.
 *
 * Message bodies leave this class already sanitised and already wrapped in the
 * standalone document the reader iframe expects. Nothing hands raw sender HTML to
 * the browser, not even briefly. See MailHtmlSanitizer for the four layers.
 */
@RestController
@RequestMapping("/api/mail")
public class MailApiController {

    private static final int MAX_PAGE = 100;

    /**
     * Files on one message. The byte budget already bounds how much can arrive, but
     * not how many parts it arrives in, and every part is one round trip to the blob
     * store: a thousand one-byte files would be a thousand uploads inside a single
     * request that the size limit would happily wave through.
     */
    private static final int MAX_FILES = 20;

    /** type/subtype and nothing else. Anchored by matches(), so a newline cannot hide in it. */
    private static final Pattern MIME_TYPE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,62}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,62}");

    /** Folders whose rows should show who the message went TO rather than who sent it. */
    private static final List<String> OUTGOING_ROLES = List.of("sent", "drafts");

    private final MailService mail;
    private final MailboxAccess mailbox;
    private final AuditService audit;
    private final MessageLogService messageLog;
    /** Only the attachment route needs the raw client; everything else goes through MailService. */
    private final JmapClient jmap;
    /** The same counters the login form spends, because it is the same secret. */
    private final LoginRateLimiter limiter;

    public MailApiController(MailService mail, MailboxAccess mailbox, AuditService audit,
                             MessageLogService messageLog, JmapClient jmap, LoginRateLimiter limiter) {
        this.mail = mail;
        this.mailbox = mailbox;
        this.audit = audit;
        this.messageLog = messageLog;
        this.jmap = jmap;
        this.limiter = limiter;
    }

    // ------------------------------------------------------------------ session

    /** What the screen asks first: is a mailbox open, and which one. Never 409s. */
    @GetMapping("/status")
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public Map<String, Object> status(Authentication auth, HttpSession session) {
        String open = mailbox.current(auth, session);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unlocked", open != null);
        out.put("mailbox", nz(open));
        // A sensible default for the unlock prompt, not an authorisation of any kind.
        out.put("suggested", auth == null ? "" : nz(auth.getName()));
        // The compose sheet needs the attachment budget before anybody attaches
        // anything, and this is the one call it already makes on every load. Sending
        // it here means the limit is stated in exactly one place, the server, rather
        // than as a constant in the script that goes stale the day it is retuned.
        out.put("attachmentLimit", mail.maxAttachmentBytes());
        out.put("attachmentMaxFiles", MAX_FILES);
        return out;
    }

    /**
     * Opens a mailbox from the unlock prompt, under the same counters the login form
     * uses and leaving a row behind whichever way it goes.
     *
     * Both of those are recent and both were holes. This endpoint offers an address
     * and a password to the mail server and answers within one round trip whether the
     * pair was accepted, with no limit of any kind on how often, which is a password
     * oracle for every mailbox in the organisation; and it wrote an audit row only
     * when an open succeeded, so a sweep through the whole domain that guessed wrong
     * every time left nothing at all in the log to say it had happened. The two
     * together meant the cheapest way to attack mail was also the quietest one. It is
     * behind a console session and MAIL_READ, which raises the price of starting but
     * does nothing about the rate once anybody is in, and a mailbox password is
     * exactly what a signed-in mail-only session does not have for anybody else.
     *
     * It shares LoginRateLimiter with the login form rather than counting separately,
     * because it is the same secret being guessed at and two independent budgets for
     * one secret is just twice the budget. The address side of that is a delay and not
     * a refusal, so the person whose mailbox is being guessed at can still open their
     * own mail; the refusal below can only ever be the per-client cap.
     */
    @PostMapping("/unlock")
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public ResponseEntity<?> unlock(HttpSession session,
                                    HttpServletRequest request,
                                    @RequestParam String address,
                                    @RequestParam String password) {
        LoginRateLimiter.Decision decision =
                limiter.reserve(address, LoginRateLimiter.clientKey(request));
        if (decision.refused()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()))
                    .body(Map.of("error", "Too many attempts from this connection. Wait a few minutes."));
        }
        decision.pause();

        try {
            String opened = mailbox.open(session, address, password);
            limiter.succeeded(address);
            // The password itself is never an argument to anything that persists. Only
            // the fact that this console account opened that mailbox is worth keeping.
            audit.record("MAILBOX_OPENED", opened, "webmail session");
            return ResponseEntity.ok(Map.of("ok", true, "mailbox", opened));
        } catch (MailException e) {
            // Caught narrowly on purpose. Every way this endpoint can turn a password
            // down arrives as a MailException, and anything else reaching here is a
            // fault in the application rather than somebody being refused, which is a
            // different row in a different log and not this one.
            audit.record("MAILBOX_OPEN_FAILED", clip(LoginAddress.canonical(address)),
                    e.getKind() == MailException.Kind.AUTH
                            ? "The mail server refused that mailbox password."
                            : "Refused before the mail server was asked: " + e.getKind());
            throw e;
        }
    }

    @PostMapping("/lock")
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public Map<String, Object> lock(Authentication auth, HttpSession session) {
        String was = mailbox.current(auth, session);
        mailbox.close(auth, session);
        if (was != null) audit.record("MAILBOX_CLOSED", was, "webmail session");
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ read

    @GetMapping("/folders")
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public Map<String, Object> folders(Authentication auth, HttpSession session) {
        String user = mailbox.require(auth, session);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MailFolder f : mail.listFolders(user)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", nz(f.id()));
            m.put("name", nz(f.name()));
            m.put("role", nz(f.role()));
            m.put("total", f.totalEmails());
            m.put("unread", f.unreadEmails());
            rows.add(m);
        }
        return Map.of("folders", rows, "mailbox", user);
    }

    @GetMapping("/messages")
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public Map<String, Object> messages(Authentication auth,
                                        HttpSession session,
                                        @RequestParam String folder,
                                        @RequestParam(defaultValue = "") String role,
                                        @RequestParam(defaultValue = "0") int offset,
                                        @RequestParam(defaultValue = "40") int limit) {
        String user = mailbox.require(auth, session);
        MessagePage page = mail.listMessages(user, folder, Math.max(0, offset), clampLimit(limit));
        // role is cosmetic and nothing else: it decides whether a row names the sender
        // or the recipient. It is taken from the request rather than resolved with a
        // second Mailbox/get because a wrong value costs one misdrawn line of text,
        // and an extra JMAP round trip on every page of every folder is not worth that.
        return pageJson(page, summaries(page.messages(), isOutgoing(role)));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public Map<String, Object> search(Authentication auth,
                                      HttpSession session,
                                      @RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "0") int offset,
                                      @RequestParam(defaultValue = "40") int limit) {
        String user = mailbox.require(auth, session);
        MessagePage page = mail.search(user, q.trim(), Math.max(0, offset), clampLimit(limit));

        // Search crosses folders, so a row's sender is the only honest thing to show.
        Map<String, Object> out = pageJson(page, summaries(page.messages(), false));
        out.put("q", q.trim());
        return out;
    }

    /**
     * The reading pane. images defaults to false: a remote image in a message is a
     * read receipt for the sender, so turning them on is a deliberate second request
     * rather than something the page can decide for itself.
     */
    @GetMapping("/message")
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public Map<String, Object> message(Authentication auth,
                                       HttpSession session,
                                       @RequestParam String id,
                                       @RequestParam(defaultValue = "false") boolean images,
                                       @RequestParam(defaultValue = "auto") String theme) {
        String user = mailbox.require(auth, session);
        MessageBody body = mail.getMessage(user, id);

        MailHtmlSanitizer.Result reader =
                MailHtmlSanitizer.toReaderDocument(body.html(), body.text(), images, theme);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", nz(body.id()));
        out.put("threadId", nz(body.threadId()));
        out.put("subject", nz(body.subject()));
        out.put("from", address(first(body.from())));
        out.put("to", addresses(body.to()));
        out.put("cc", addresses(body.cc()));
        out.put("receivedAt", iso(body.receivedAt()));
        out.put("sentAt", iso(body.sentAt()));
        out.put("seen", body.seen());
        out.put("flagged", body.flagged());
        out.put("bodyHtml", reader.html());
        out.put("blockedImages", reader.blockedImages());
        out.put("imagesShown", images);

        // files() rather than attachments(), so the inline images the body already
        // references with cid: are not also listed as paperclips.
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Attachment a : body.files()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", nz(a.safeName()));
            m.put("type", nz(a.type()));
            m.put("size", a.size());
            m.put("blobId", nz(a.blobId()));
            parts.add(m);
        }
        out.put("attachments", parts);
        return out;
    }

    /**
     * Attachment bytes. The message is re-read rather than trusting the blob id on
     * the query string: without that, a blob id would be an unauthenticated
     * capability and anyone could pull a blob out of a message they were never sent.
     */
    @GetMapping("/attachment")
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public ResponseEntity<byte[]> attachment(Authentication auth,
                                             HttpSession session,
                                             @RequestParam String id,
                                             @RequestParam String blobId) {
        String user = mailbox.require(auth, session);
        MessageBody body = mail.getMessage(user, id);

        Attachment file = body.files().stream()
                .filter(a -> blobId.equals(a.blobId()))
                .findFirst()
                .orElseThrow(() -> new MailException(MailException.Kind.NOT_FOUND, "No such attachment"));

        byte[] bytes = jmap.download(user, file.blobId(), file.safeName(), file.type());
        audit.record("MAIL_ATTACHMENT_DOWNLOADED", user, file.safeName());

        return ResponseEntity.ok()
                // Served as an opaque download whatever the declared type, so a
                // text/html attachment can never execute against our own origin.
                .header("Content-Type", "application/octet-stream")
                .header("Content-Disposition",
                        "attachment; filename=\"" + file.safeName().replace("\"", "").replace("\n", "") + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes);
    }

    // ------------------------------------------------------------------ mutate

    /** Marking read is part of reading, so it sits under the same permission. */
    @PostMapping("/read")
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public Map<String, Object> markRead(Authentication auth,
                                        HttpSession session,
                                        @RequestParam String id,
                                        @RequestParam(defaultValue = "true") boolean value) {
        mail.setKeyword(mailbox.require(auth, session), id, "seen", value);
        return Map.of("ok", true);
    }

    @PostMapping("/flag")
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public Map<String, Object> flag(Authentication auth,
                                    HttpSession session,
                                    @RequestParam String id,
                                    @RequestParam(defaultValue = "true") boolean value) {
        mail.setKeyword(mailbox.require(auth, session), id, "flagged", value);
        return Map.of("ok", true);
    }

    // Moving and deleting can lose mail, so they need the stronger permission. There
    // is no MAIL_WRITE in the enum; MAIL_SEND is the one that marks a full operator.
    @PostMapping("/move")
    @PreAuthorize("hasAuthority('MAIL_SEND')")
    public Map<String, Object> move(Authentication auth,
                                    HttpSession session,
                                    @RequestParam String id,
                                    @RequestParam String folder) {
        String user = mailbox.require(auth, session);
        mail.move(user, id, folder);
        audit.record("MAIL_MOVED", user, "message " + id + " to folder " + folder);
        return Map.of("ok", true);
    }

    /**
     * First delete moves to Trash, second one destroys, which is MailService.delete's
     * contract and the behaviour every mail client has trained people to expect.
     */
    @PostMapping("/delete")
    @PreAuthorize("hasAuthority('MAIL_SEND')")
    public Map<String, Object> delete(Authentication auth,
                                      HttpSession session,
                                      @RequestParam String id) {
        String user = mailbox.require(auth, session);
        mail.delete(user, id);
        audit.record("MAIL_DELETED", user, "message " + id);
        return Map.of("ok", true);
    }

    /**
     * One to one send. The composer is a plain textarea, so the HTML part is built
     * here from the typed text rather than accepted from the browser. That keeps this
     * server out of the business of relaying markup it did not write.
     *
     * Takes an ordinary form post and a multipart one through the same method. A
     * MultipartFile array bound with required=false resolves to null when the request
     * carries no parts at all, so every existing caller sends exactly what it sent
     * before and gets exactly what it got before; files only exist once a browser has
     * actually attached some. No parameter name, success body or failure body changed.
     */
    @PostMapping("/send")
    @PreAuthorize("hasAuthority('MAIL_SEND')")
    public ResponseEntity<?> send(Authentication auth,
                                  HttpSession session,
                                  @RequestParam String to,
                                  @RequestParam(required = false) String cc,
                                  @RequestParam(defaultValue = "") String subject,
                                  @RequestParam(defaultValue = "") String body,
                                  @RequestParam(value = "files", required = false) MultipartFile[] files) {
        String user = mailbox.require(auth, session);
        List<String> recipients = addressList(to);
        if (recipients.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Add at least one recipient."));
        }
        List<String> copies = addressList(cc);

        // Everything that can refuse this message is decided before a single byte
        // leaves for the mail server, so a file we will not carry costs the sender
        // the upload they already made and nothing beyond it.
        List<MultipartFile> chosen = chosenFiles(files);
        String refusal = refuse(chosen);
        if (refusal != null) {
            audit.record("MAIL_ATTACHMENT_REFUSED", user, clip(refusal));
            return ResponseEntity.badRequest().body(Map.of("error", refusal));
        }

        long startedAt = System.currentTimeMillis();
        List<Attachment> attached = uploadAll(user, chosen);
        String sentId = mail.send(user, recipients, copies, subject.trim(), composeHtml(body), body, attached);
        long elapsed = System.currentTimeMillis() - startedAt;
        // AuditLog.target is a plain 255 char column and only detail is truncated for us.
        audit.record("MAIL_SENT", clip(user + " to " + String.join(", ", recipients)),
                "subject " + (subject.isBlank() ? "(none)" : subject.trim())
                        + (sentId == null ? "" : ", id " + sentId)
                        + (attached.isEmpty() ? "" : ", files " + names(attached)));

        // One row per address, the same shape campaign and transactional sends
        // write, so "did that one email go out" is one search whichever screen it
        // was sent from. The mail server has only accepted it at this point;
        // StalwartDeliveryLog fills in what the receiving server said.
        for (String recipient : recipients) logSend(user, recipient, subject, elapsed);
        for (String recipient : copies) logSend(user, recipient, subject, elapsed);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "message", "Sent to " + String.join(", ", recipients)
                        + (attached.isEmpty() ? "."
                        : " with " + attached.size() + (attached.size() == 1 ? " file." : " files."))));
    }

    // ------------------------------------------------------------------ attachments

    /**
     * A file input submitted with nothing chosen still arrives as a part, and an
     * empty part is not a file anybody meant to attach.
     */
    private static List<MultipartFile> chosenFiles(MultipartFile[] files) {
        List<MultipartFile> out = new ArrayList<>();
        if (files == null) return out;
        for (MultipartFile f : files) {
            if (f != null && !f.isEmpty()) out.add(f);
        }
        return out;
    }

    /**
     * The sentence to answer with instead of sending, or null when the files are
     * fine. One sentence rather than a list of every problem, because the compose
     * sheet shows it in a toast and the first thing to fix is the useful thing to say.
     */
    private String refuse(List<MultipartFile> files) {
        if (files.isEmpty()) return null;
        if (files.size() > MAX_FILES) {
            return "That is " + files.size() + " files on one message. Send at most " + MAX_FILES
                    + ", or put them in a shared folder and send the link.";
        }

        long total = 0;
        for (MultipartFile file : files) {
            String refused = Attachment.refusedExtension(file.getOriginalFilename());
            // The saved name is checked as well as the sent one. safeName rewrites
            // characters that are not letters, digits or a short punctuation set, so
            // a name can arrive looking like one thing and land as another.
            if (refused == null) refused = Attachment.refusedExtension(displayName(file));
            if (refused != null) {
                return displayName(file) + " was not sent. A ." + refused
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
                    + mb(cap) + " a message can carry. Email inflates every attachment by about a"
                    + " third, which is why the limit is below the size you see on disk."
                    + " Remove one, or send a link instead.";
        }
        return null;
    }

    /**
     * Puts every file in the account's blob store and returns the parts that point at
     * them. Nothing here holds a file: the bytes go from the temp file Spring wrote
     * straight onto the socket.
     *
     * Sequential on purpose. These are large bodies over a single loopback connection
     * to a single mail server, so uploading four at once buys no wire time and
     * multiplies the buffers in flight on a box with two vCPUs. The slow half of a
     * 20MB attachment is the phone to server hop, and that has already finished by
     * the time this method runs.
     */
    private List<Attachment> uploadAll(String user, List<MultipartFile> files) {
        List<Attachment> out = new ArrayList<>();
        for (MultipartFile file : files) {
            // Opened per attempt rather than once, because the HTTP client may ask the
            // publisher for the body a second time and a stream already drained would
            // send a truncated file with nothing anywhere reporting a failure.
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

    /**
     * The declared type is chosen by whatever posted the form and is about to become
     * a Content-Type header on a request of ours, so it is treated as untrusted text:
     * parameters after the semicolon are dropped and anything that is not a plain
     * type/subtype is replaced outright. A newline smuggled through this field can
     * therefore never split a header on the way to the mail server.
     */
    private static String safeType(String declared) {
        if (declared == null) return "application/octet-stream";
        String type = declared.trim();
        int semi = type.indexOf(';');
        if (semi >= 0) type = type.substring(0, semi).trim();
        return MIME_TYPE.matcher(type).matches()
                ? type.toLowerCase(Locale.ROOT)
                : "application/octet-stream";
    }

    /** What the file is called in a message to a person, already stripped of anything hostile. */
    private static String displayName(MultipartFile file) {
        return new Attachment(null, null, file.getOriginalFilename(), null, 0L, null, null).safeName();
    }

    private static String names(List<Attachment> files) {
        List<String> all = new ArrayList<>();
        for (Attachment f : files) all.add(f.safeName());
        return String.join(", ", all);
    }

    /**
     * One decimal, because 17.0 and 16.9 is a difference a sender can act on, and
     * 1024 based because that is what the compose sheet's own bytes() shows beside
     * every file. A limit stated as 17.0 next to a total counted as 16.2 would be
     * two different numbers for one rule.
     */
    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576d);
    }

    // ------------------------------------------------------------------ plumbing

    /** Never allowed to fail a send that already succeeded, hence the swallow inside the service. */
    private void logSend(String mailboxAddress, String recipient, String subject, long elapsed) {
        messageLog.record("OUTBOUND", mailboxAddress, recipient,
                subject == null || subject.isBlank() ? "(no subject)" : subject.trim(),
                null, null, null, "SENT",
                "Accepted by the mail server for delivery", elapsed, mailboxAddress);
    }

    private static int clampLimit(int limit) {
        return Math.min(MAX_PAGE, Math.max(1, limit));
    }

    private static Map<String, Object> pageJson(MessagePage page, List<Map<String, Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", rows);
        out.put("total", page.total());
        out.put("offset", page.offset());
        out.put("limit", page.limit());
        return out;
    }

    /** Sent and Drafts rows name the recipient. Everywhere else names the sender. */
    private static boolean isOutgoing(String role) {
        return role != null && OUTGOING_ROLES.contains(role.trim().toLowerCase(Locale.ROOT));
    }

    private static List<Map<String, Object>> summaries(List<MessageSummary> list, boolean outgoing) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (list == null) return rows;
        for (MessageSummary s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", nz(s.id()));
            m.put("threadId", nz(s.threadId()));
            m.put("from", address(s.counterparty(outgoing)));
            m.put("subject", nz(s.subject()));
            m.put("preview", nz(s.preview()));
            m.put("receivedAt", iso(s.receivedAt()));
            m.put("seen", s.seen());
            m.put("flagged", s.flagged());
            m.put("hasAttachment", s.hasAttachment());
            m.put("size", s.size());
            rows.add(m);
        }
        return rows;
    }

    private static MailAddress first(List<MailAddress> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private static Map<String, Object> address(MailAddress a) {
        if (a == null) return Map.of("name", "", "email", "", "display", "");
        return Map.of("name", nz(a.name()), "email", nz(a.email()), "display", nz(a.display()));
    }

    private static List<Map<String, Object>> addresses(List<MailAddress> list) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (list == null) return rows;
        for (MailAddress a : list) rows.add(address(a));
        return rows;
    }

    /** Accepts the comma or semicolon separated list a person actually types. */
    private static List<String> addressList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split("[,;]")) {
            String v = part.trim();
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
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
        return out.isEmpty() ? "<p></p>" : out.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String iso(Instant when) {
        return when == null ? "" : when.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s) {
        return s.length() <= 240 ? s : s.substring(0, 237) + "...";
    }

    // ------------------------------------------------------------------ failures

    /**
     * 409 rather than 401, and the distinction is the whole point: 401 means the
     * console session has gone and the browser should go to /login, while this means
     * the console session is fine and only the mailbox needs opening. Answering 401
     * here would bounce a signed-in user back to a login page that fixes nothing.
     */
    @ExceptionHandler(MailboxAccess.MailboxLockedException.class)
    public ResponseEntity<?> onLocked(MailboxAccess.MailboxLockedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage(), "locked", true));
    }

    /**
     * The mail package raises one exception type with a kind that maps onto a status,
     * which is the reason it was built that way. AUTH is the interesting one: Stalwart
     * refusing the mailbox password is the same situation as never having had it, so
     * it comes back as a locked mailbox and the screen re-prompts.
     */
    @ExceptionHandler(MailException.class)
    public ResponseEntity<?> onMailFailure(MailException e) {
        String message = e.getMessage() == null ? "The mail server refused that." : e.getMessage();
        return switch (e.getKind()) {
            case AUTH -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", message, "locked", true));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", message));
            // PROTOCOL covers both "you sent something malformed" and "we could not
            // parse the answer". In this controller it is nearly always the former,
            // a typed address or an empty folder id, so it reads as a 400.
            case PROTOCOL -> ResponseEntity.badRequest().body(Map.of("error", message));
            default -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "The mail server did not answer: " + message));
        };
    }

    /** A junk offset or limit is the caller's mistake, not the mail server's. */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<?> onBadParam(TypeMismatchException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Bad request parameter."));
    }

    /**
     * An upload over the container's own ceiling, or a multipart body that did not
     * parse. A sentence, so the compose sheet has something to put in a toast.
     *
     * This is the outer backstop and not the limit anybody meets. The mail limit in
     * refuse() is well below spring.servlet.multipart.max-request-size, so every
     * oversized attachment a person can actually assemble is turned down there, by
     * name and with the encoded size spelled out.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> onUploadTooBig(MultipartException e) {
        String message = e instanceof MaxUploadSizeExceededException
                ? "That upload is bigger than this server will take in one request. "
                        + "Attach less than " + mb(mail.maxAttachmentBytes()) + " of files."
                : "That upload did not arrive in one piece. Nothing was sent. Attach the files again.";
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of("error", message));
    }

    /**
     * The temp file Spring wrote for an upload could not be read back, which is a disk
     * fault or a cleanup race and never the sender's doing. Handled by name so it does
     * not reach the catch-all below and come out as "UncheckedIOException", which
     * tells the person reading the toast nothing they can do anything about.
     */
    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<?> onUploadUnreadable(UncheckedIOException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "An attached file could not be read back off this server. "
                        + "Nothing was sent. Attach it again."));
    }

    /**
     * Last resort, so an unexpected failure is a sentence on screen rather than a
     * stack trace naming internal hosts.
     *
     * The rethrow is not optional. @PreAuthorize denials arrive here as an
     * AccessDeniedException, which is a RuntimeException, so a handler this broad
     * would quietly turn every 403 into a 502. Letting it past returns it to the
     * security filter chain, which is the only thing entitled to answer an
     * authorisation failure.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> onFailure(RuntimeException e) {
        if (e instanceof AccessDeniedException) throw e;
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "The mail screen hit an unexpected fault: "
                        + e.getClass().getSimpleName()));
    }
}
