package com.jarurat.mailer.models;

import com.jarurat.mailer.mail.Attachment;
import com.jarurat.mailer.mail.Outgoing;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One message this server has agreed to send later, and the whole of what it needs
 * to send it.
 *
 * This is the one place in the mailbox feature set that earns a Postgres table, and
 * the reason is narrow enough to write down. Everything else the webmail does is
 * mailbox state, so Stalwart owns it and a second copy here would disagree with
 * Thunderbird the first time somebody opened the account there. A held send is not
 * mailbox state: it is a promise this application made to a person at a moment when
 * the mail server had not been told anything at all, and there is nowhere on the mail
 * server to keep a promise that has not been made to it yet. Handing it to Stalwart
 * early as a FUTURERELEASE submission would be the better design, and it is the one
 * to move to the day somebody reads maxDelayedSend off the live session document,
 * which phase 0 of the plan asks for and nobody has run.
 *
 * The row carries the message rather than a pointer to a draft, deliberately. A
 * pointer would mean a person editing that draft on a phone at 8am silently changing
 * what goes out at 9am, and it would mean a deleted draft turning a scheduled send
 * into a failure at the one moment nobody is watching. What is queued is what was
 * written when the send button was pressed.
 *
 * Every timestamp on this row is truncated to whole seconds on the way in, by the
 * setters and the constructor rather than by the callers, because the callers will
 * forget. Postgres truncates a sub-second LocalDateTime and H2 rounds it, so 09:00:00.6
 * is stored as 09:00:00 on the box and 09:00:01 in the test suite, and a due query of
 * sendAt before now then finds a row in production that the same test cannot see.
 * Truncating at the boundary makes the two agree by construction.
 */
@Entity
@Table(name = "queued_message",
        indexes = {
                @Index(name = "idx_qm_due", columnList = "state,sendAt"),
                @Index(name = "idx_qm_mailbox", columnList = "mailbox,state,sendAt")
        })
public class QueuedMessage {

    /** Waiting for its time. The only state from which anything else may happen. */
    public static final String HELD = "HELD";

    /** Claimed by the sender loop. Nothing outside that loop may touch it again. */
    public static final String SENDING = "SENDING";

    /** The mail server accepted it. sentEmailId names the copy in Sent. */
    public static final String SENT = "SENT";

    /** Cancelled inside its window, or replaced by an edit. Nothing was transmitted. */
    public static final String CANCELLED = "CANCELLED";

    /** It did not go out and the person needs to be told. lastError says what happened. */
    public static final String FAILED = "FAILED";

    /** An ordinary send, held only long enough that it can be taken back. */
    public static final String UNDO = "UNDO";

    /** A send somebody picked a time for. */
    public static final String SCHEDULED = "SCHEDULED";

    /**
     * Field separator inside the packed attachment list.
     *
     * ASCII 31, the unit separator, rather than a comma or a tab, because a file name
     * reaches this row through Attachment.safeName, which keeps spaces and a short
     * punctuation set, and a name holding the separator would split one file into two
     * rows
     * pointing at nothing. No name that survives safeName can hold a control
     * character, so this one cannot collide with anything a person types.
     */
    private static final String FIELD = String.valueOf((char) 31);

    private static final String LINE = "\n";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The mailbox that queued it, lowercased. This is the isolation key and it is on
     * every query in the repository, because a queued message is the one piece of
     * webmail state that outlives the browser session that made it, so there is no
     * session pin left to lean on when it is read back.
     */
    @Column(nullable = false, length = 320)
    private String mailbox;

    /** UNDO or SCHEDULED. Cosmetic to the sender loop, which treats both identically. */
    @Column(nullable = false, length = 16)
    private String kind = UNDO;

    @Column(nullable = false, length = 16)
    private String state = HELD;

    /** When it may go, and the moment it stops being cancellable. */
    @Column(nullable = false)
    private LocalDateTime sendAt;

    @Column(nullable = false)
    private LocalDateTime queuedAt;

    /** When the sender loop took ownership. Null until it does. */
    private LocalDateTime claimedAt;

    /** When it reached SENT, CANCELLED or FAILED. */
    private LocalDateTime settledAt;

    /** How many times the loop has claimed it. A retry is a second claim. */
    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(length = 4000)
    private String toAddresses;

    @Column(length = 4000)
    private String ccAddresses;

    @Column(length = 4000)
    private String bccAddresses;

    @Column(length = 500)
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String html;

    @Column(columnDefinition = "TEXT")
    private String textBody;

    @Column(length = 512)
    private String inReplyTo;

    @Column(length = 4000)
    private String messageReferences;

    /**
     * The files, as blob ids the mail server already holds. The bytes were uploaded
     * when the message was queued, so this row never carries a payload and a queue of
     * fifty messages is not fifty attachments sitting in Postgres.
     */
    @Column(columnDefinition = "TEXT")
    private String attachments;

    /** The parent this answers, so the reply arrow is set once the reply has gone. */
    @Column(length = 255)
    private String replyToEmailId;

    /** The Sent copy, once there is one. */
    @Column(length = 255)
    private String sentEmailId;

    /** Set when an edit replaced this row, so a cancelled row can still explain itself. */
    private Long replacedById;

    @Column(length = 400)
    private String lastError;

    /**
     * When the person has seen a failure. A failed row stays in the outbox listing
     * until this is set, which is what stops a 3am failure from being a line in a log
     * nobody reads.
     */
    private LocalDateTime acknowledgedAt;

    public QueuedMessage() {}

    public QueuedMessage(String mailbox, String kind, LocalDateTime sendAt,
                         LocalDateTime queuedAt, Outgoing message, String replyToEmailId) {
        this.mailbox = mailbox == null ? null : mailbox.trim().toLowerCase(Locale.ROOT);
        this.kind = kind;
        this.sendAt = whole(sendAt);
        this.queuedAt = whole(queuedAt);
        this.replyToEmailId = clip(replyToEmailId, 255);
        this.toAddresses = join(message.to());
        this.ccAddresses = join(message.cc());
        this.bccAddresses = join(message.bcc());
        this.subject = clip(message.subject(), 500);
        this.html = message.html();
        this.textBody = message.text();
        this.inReplyTo = clip(message.inReplyTo(), 512);
        this.messageReferences = join(message.references());
        this.attachments = pack(message.attachments());
    }

    /**
     * The message as the send path wants it, rebuilt from the columns.
     *
     * Going back out through Outgoing rather than through a second set of arguments is
     * the point. What leaves here is provably the same shape an immediate send builds,
     * so the blind copy guarantee, the recipient cap and the threading headers are one
     * implementation on both paths and cannot drift apart.
     */
    public Outgoing toOutgoing() {
        return new Outgoing(split(toAddresses), split(ccAddresses), split(bccAddresses),
                subject, html, textBody, unpack(attachments), inReplyTo, split(messageReferences));
    }

    /**
     * Whole seconds, everywhere, always.
     *
     * Public and static so the service can put every timestamp it computes through the
     * same call, the now it compares against included, rather than trusting that each
     * field happened to reach the setter that would have done it.
     */
    public static LocalDateTime whole(LocalDateTime when) {
        return when == null ? null : when.truncatedTo(ChronoUnit.SECONDS);
    }

    /** Everyone this message will reach, in the order the envelope will name them. */
    public List<String> everyRecipient() {
        return toOutgoing().everyRecipient();
    }

    public boolean isPending() {
        return HELD.equals(state) || SENDING.equals(state);
    }

    /**
     * Whether cancelling is still honest, which is a stricter question than whether it
     * would happen to work.
     *
     * A row whose time has passed may well still be sitting in HELD, because the
     * sender loop only looks every couple of seconds, so a cancel arriving inside that
     * gap would succeed. It is refused anyway. A control that works when the loop is a
     * second behind and fails when it is not is a control nobody can trust, and the
     * person pressing it is entitled to be told something about their message rather
     * than something about our polling interval. The deadline the screen counts down
     * to is sendAt, and this answers exactly that question.
     */
    public boolean cancellableAt(LocalDateTime now) {
        return HELD.equals(state) && sendAt != null && sendAt.isAfter(whole(now));
    }

    public Long getId() { return id; }
    public String getMailbox() { return mailbox; }
    public String getKind() { return kind; }
    public String getState() { return state; }
    public LocalDateTime getSendAt() { return sendAt; }
    public LocalDateTime getQueuedAt() { return queuedAt; }
    public LocalDateTime getClaimedAt() { return claimedAt; }
    public LocalDateTime getSettledAt() { return settledAt; }
    public int getAttempts() { return attempts == null ? 0 : attempts; }
    public String getSubject() { return subject == null ? "" : subject; }
    public String getHtml() { return html; }
    public String getTextBody() { return textBody; }
    public String getInReplyTo() { return inReplyTo; }
    public String getReplyToEmailId() { return replyToEmailId; }
    public String getSentEmailId() { return sentEmailId; }
    public Long getReplacedById() { return replacedById; }
    public String getLastError() { return lastError; }
    public LocalDateTime getAcknowledgedAt() { return acknowledgedAt; }
    public List<String> getTo() { return split(toAddresses); }
    public List<String> getCc() { return split(ccAddresses); }
    public List<String> getBcc() { return split(bccAddresses); }
    public List<Attachment> getAttachments() { return unpack(attachments); }

    public void setState(String v) { this.state = v; }
    public void setKind(String v) { this.kind = v; }
    public void setSendAt(LocalDateTime v) { this.sendAt = whole(v); }
    public void setQueuedAt(LocalDateTime v) { this.queuedAt = whole(v); }
    public void setClaimedAt(LocalDateTime v) { this.claimedAt = whole(v); }
    public void setSettledAt(LocalDateTime v) { this.settledAt = whole(v); }
    public void setAttempts(int v) { this.attempts = v; }
    public void setSentEmailId(String v) { this.sentEmailId = clip(v, 255); }
    public void setReplacedById(Long v) { this.replacedById = v; }
    public void setAcknowledgedAt(LocalDateTime v) { this.acknowledgedAt = whole(v); }
    public void setLastError(String v) { this.lastError = clip(v, 400); }

    // ------------------------------------------------------------------ packing

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    private static List<String> split(String packed) {
        if (packed == null || packed.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : packed.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    /**
     * The files as one text column rather than as a second table.
     *
     * A child table is the textbook answer and it would buy nothing here. No query
     * ever asks about an attachment on its own, the list is bounded by the same twenty
     * file limit the compose sheet enforces, and a join on the sender loop's hot path
     * costs more than it saves. What a child table would add is a second row that can
     * outlive its parent, which is the failure mode this whole feature is trying to
     * avoid rather than acquire.
     */
    private static String pack(List<Attachment> files) {
        if (files == null || files.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (Attachment file : files) {
            if (file == null || file.blobId() == null) continue;
            if (out.length() > 0) out.append(LINE);
            out.append(file.blobId()).append(FIELD)
                    .append(file.safeName()).append(FIELD)
                    .append(file.type() == null ? "application/octet-stream" : file.type()).append(FIELD)
                    .append(file.size());
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static List<Attachment> unpack(String packed) {
        if (packed == null || packed.isBlank()) return List.of();
        List<Attachment> out = new ArrayList<>();
        for (String line : packed.split(LINE)) {
            if (line.isBlank()) continue;
            String[] parts = line.split(FIELD, -1);
            if (parts.length < 4) continue;
            long size;
            try {
                size = Long.parseLong(parts[3]);
            } catch (NumberFormatException e) {
                // A size that did not survive the round trip is a display detail and
                // never the thing that decides whether a message goes out, so it costs
                // a zero in the file list rather than a send.
                size = 0L;
            }
            out.add(Attachment.outgoing(parts[0], parts[1], parts[2], size));
        }
        return out;
    }

    private static String clip(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public String toString() {
        return "QueuedMessage[" + id + " " + mailbox + " " + state + " at " + sendAt + "]";
    }
}
