package com.jarurat.mailer.models;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One row of preferences for one mailbox address.
 *
 * THE KEY IS THE MAILBOX ADDRESS AND NOT AN APP_USER ID, AND THAT IS THE WHOLE POINT
 * ------------------------------------------------------------------------------------
 * Campaign Studio and the mail server are two separate identity systems, and
 * MailboxAccess exists only because nothing else bridges them. A person can reach the
 * mailbox screen through MailboxAuthenticationProvider having proved a Stalwart
 * password and nothing else, in which case there is no app_user row anywhere to hang a
 * preference off, and a foreign key onto one would either refuse the insert or quietly
 * file everybody's settings under whichever console account happened to be signed in.
 * The address is also the thing the preference is actually about: a signature belongs
 * to support@ rather than to whichever of the three people who share support@ opened
 * it this morning, and an out of office reply that followed a person between mailboxes
 * would answer donors in the wrong voice.
 *
 * The consequence, stated plainly because it is a real one: colleagues who share a
 * mailbox share its settings, and the last person to save wins. That is correct for a
 * signature and for an out of office message, which are properties of the address, and
 * it is arguably wrong for the reading pane, which is a property of a person. Splitting
 * the row in two would double the storage to serve a preference nobody in a fifteen
 * person foundation has ever asked for, so it is one row and this paragraph.
 *
 * Almost nothing in here is mailbox state that another mail client could also change.
 * The rule the build plan holds to is that anything IMAP or a second JMAP client can
 * touch belongs to Stalwart, and these are instead how this application draws its own
 * screen and what it appends to a message before handing it over. The one exception is
 * the out of office reply, which genuinely belongs on the server because it has to fire
 * while nobody is signed in, so what is stored here is the request and MailSettingsApi
 * pushes it to Stalwart on every save.
 */
@Entity
@Table(name = "mailbox_settings")
public class MailboxSettings {

    /** The reading pane sits beside the list on a laptop, or under it. */
    public static final String PANE_SIDE = "side";
    public static final String PANE_BELOW = "below";
    private static final Set<String> PANES = Set.of(PANE_SIDE, PANE_BELOW);

    /** What Send does by default when a reply is started from the reader. */
    public static final String REPLY_SENDER = "reply";
    public static final String REPLY_ALL = "reply-all";
    private static final Set<String> REPLIES = Set.of(REPLY_SENDER, REPLY_ALL);

    /** The same ceiling MailApiController puts on a page, so the two cannot disagree. */
    public static final int MIN_PER_PAGE = 10;
    public static final int MAX_PER_PAGE = 100;

    /**
     * The undo window the send path would hold a message for. Thirty seconds is where
     * every client that has this feature stops, because past that the sender has moved
     * on and the delay is only costing the recipient.
     */
    public static final int MAX_UNDO_SECONDS = 30;

    /** A vacation period shorter than a day would answer a thread rather than a person. */
    public static final int MIN_PERIOD_DAYS = 1;
    public static final int MAX_PERIOD_DAYS = 30;

    /**
     * How many senders the once-per-period ledger will remember before it starts
     * forgetting the oldest.
     *
     * Expired entries are dropped on every write, so this ceiling is only ever reached
     * by a mailbox that really is being written to by thousands of distinct people
     * inside one period, which for this organisation means it is being spammed. Losing
     * the oldest entries then means the earliest of those senders could be answered a
     * second time, which is a far better failure than a row that grows without limit.
     */
    public static final int MAX_TRACKED_SENDERS = 2000;

    /**
     * Local parts that answer nobody. RFC 3834 says an automatic reply must not be sent
     * to an address that cannot receive one, and these are the ones that reach a small
     * charity inbox every day.
     */
    private static final Set<String> ROBOT_LOCAL_PARTS = Set.of(
            "noreply", "no-reply", "no_reply", "donotreply", "do-not-reply", "do_not_reply",
            "mailer-daemon", "mailerdaemon", "postmaster", "bounce", "bounces",
            "notifications", "notification", "automated", "auto-reply", "autoreply",
            "root", "daemon", "nobody", "listserv", "majordomo");

    /** Why an auto-reply was or was not sent, so a caller can log the reason. */
    public enum Reply {
        /** Send it, and the ledger has been told. */
        SEND,
        /** The out of office is switched off. */
        OFF,
        /** Switched on, but today is outside the dates. */
        OUTSIDE_WINDOW,
        /** This sender has already had one inside the period. */
        ALREADY_REPLIED,
        /** The mailbox wrote to itself, and answering would be a loop. */
        SELF,
        /** The address cannot receive a reply, or the message asked not to be answered. */
        AUTOMATED,
        /** The message came from a mailing list, and answering one answers everybody. */
        LIST,
        /** No address to answer. */
        NO_SENDER
    }

    /** Lower cased, always. The address is the identity, so it has to compare exactly. */
    @Id
    @Column(name = "mailbox", length = 320, nullable = false)
    private String mailbox;

    // ---------------------------------------------------------------- signature

    /**
     * Already through OutboundHtml.clean by the time it lands here. A signature is HTML
     * somebody typed into a browser and it is put in front of donors and hospitals, so
     * it goes through exactly the allowlist an outgoing message goes through and not a
     * second, looser one written for a field that felt harmless.
     */
    @Column(name = "signature_html", length = 8000)
    private String signatureHtml = "";

    @Column(name = "signature_on_new", nullable = false)
    private boolean signatureOnNew = true;

    /**
     * Separate from the new-message choice on purpose. Signing the first message to a
     * hospital is courtesy; signing the fourth line of a back and forth is four copies
     * of a phone number in the quoted history, which is why every client that has this
     * feature has two switches and not one.
     */
    @Column(name = "signature_on_reply", nullable = false)
    private boolean signatureOnReply = false;

    // ---------------------------------------------------------------- out of office

    @Column(name = "vacation_enabled", nullable = false)
    private boolean vacationEnabled = false;

    @Column(name = "vacation_subject", length = 300)
    private String vacationSubject = "";

    @Column(name = "vacation_html", length = 8000)
    private String vacationHtml = "";

    /** Null means from now, which is what somebody switching it on at the airport means. */
    @Column(name = "vacation_from")
    private Instant vacationFrom;

    /** Null means until it is switched off again. */
    @Column(name = "vacation_to")
    private Instant vacationTo;

    /**
     * The once-per-sender period, in days.
     *
     * Seven is what RFC 5230 makes the default for Sieve vacation and what every
     * serious implementation settled on, because a week is longer than a normal thread
     * and shorter than a normal absence. This is the number that keeps an auto responder
     * from becoming an incident: without it, a mailing list thread with forty messages
     * produces forty identical replies to the list, and the list operator removes the
     * address.
     */
    @Column(name = "vacation_period_days", nullable = false)
    private int vacationPeriodDays = 7;

    /**
     * Whether the last save reached the mail server own vacation responder.
     *
     * True is the answer that matters, because it means the replies happen at the mail
     * server whether or not anybody has this screen open, which is the entire point of
     * an out of office. False means the settings are stored and honest about being
     * inert, and the screen says so rather than implying an absence is covered.
     */
    @Column(name = "vacation_server_side", nullable = false)
    private boolean vacationServerSide = false;

    /** What the mail server said, kept verbatim so a failure can be diagnosed later. */
    @Column(name = "vacation_server_note", length = 500)
    private String vacationServerNote = "";

    @Column(name = "vacation_synced_at")
    private Instant vacationSyncedAt;

    /**
     * Who has already had an automatic reply, and when.
     *
     * Eager because it is read on the same path that reads the row and pruned on every
     * write, so it is a handful of entries rather than a table scan waiting to happen,
     * and a lazy collection here would only produce a LazyInitializationException the
     * first time somebody called the rule from outside a transaction.
     *
     * This is the one piece of genuine state in this file, and it lives in Postgres
     * because it has nowhere else to live: Stalwart keeps its own ledger when the server
     * side responder is the one running, and this copy is what the application side rule
     * uses when it is not.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mailbox_auto_reply_log",
            joinColumns = @JoinColumn(name = "mailbox"))
    @MapKeyColumn(name = "sender", length = 320)
    @Column(name = "replied_at", nullable = false)
    private Map<String, Instant> autoReplied = new HashMap<>();

    // ---------------------------------------------------------------- reading

    /**
     * Whether to show the HTML part when a message carries both.
     *
     * Defaults to HTML because a newsletter with its layout stripped is unreadable, and
     * the plain text alternative many senders ship is a placeholder telling you to view
     * it in a browser. The switch exists because the opposite preference is a real one:
     * plain text cannot track you, cannot be phished as convincingly, and is faster on a
     * bad connection at a camp.
     */
    @Column(name = "reading_prefer_html", nullable = false)
    private boolean preferHtml = true;

    /**
     * Off, and it stays off unless somebody deliberately turns it on.
     *
     * A remote image is a read receipt. Fetching one tells the sender the message was
     * opened, at what time, from which IP address and therefore roughly from where, and
     * no consent is asked for it anywhere. That is worth more to a bulk sender than the
     * picture is worth to the reader, which is why it is the default in every client
     * that respects the people using it and why MailHtmlSanitizer withholds them by
     * construction rather than by a client side flag.
     */
    @Column(name = "reading_load_images", nullable = false)
    private boolean loadRemoteImages = false;

    @Column(name = "reading_per_page", nullable = false)
    private int messagesPerPage = 50;

    @Column(name = "reading_pane", length = 16, nullable = false)
    private String readingPane = PANE_SIDE;

    // ---------------------------------------------------------------- sending

    @Column(name = "send_undo_seconds", nullable = false)
    private int undoSendSeconds = 10;

    @Column(name = "send_default_reply", length = 16, nullable = false)
    private String defaultReply = REPLY_SENDER;

    /**
     * Off, and the default is the opinion.
     *
     * A read receipt asks the recipient mail client to report that they opened it, and a
     * charity asking a donor to be reported on is the same surveillance the image
     * blocking above refuses, only with a dialog box in front of it. It is here because
     * a hospital occasionally insists, not because it should be on.
     */
    @Column(name = "send_read_receipt", nullable = false)
    private boolean requestReadReceipt = false;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected MailboxSettings() {
    }

    public MailboxSettings(String mailbox) {
        this.mailbox = normaliseAddress(mailbox);
    }

    // ------------------------------------------------------------------
    // The once-per-sender rule
    // ------------------------------------------------------------------

    /**
     * Whether an out of office reply is owed to this sender, without recording it.
     *
     * The order of the tests is deliberate and each one is a way this feature has gone
     * wrong somewhere. Self first, because a mailbox that answers its own copy of a
     * message loops with itself at the speed of the mail server. List and automated
     * next, because those are the ones that damage somebody other than us: forty replies
     * into a mailing list thread is how an address gets removed from the list and how a
     * small charity annoys the exact community it needs. The period test is last of the
     * refusals because it is the most likely to fire, and it is what keeps a single
     * persistent correspondent from receiving one bounce-back per message for a
     * fortnight.
     *
     * automated and fromList are the caller reading of the message headers rather than
     * something derivable here. RFC 3834 puts the answer in Auto-Submitted, which must be
     * absent or no for a reply to be allowed, and the mailing list convention puts it in
     * List-Id, List-Unsubscribe or Precedence bulk or list. Passing them in keeps this
     * method a pure function of its inputs and therefore testable, which for a rule whose
     * failure mode is a mail loop is the whole point.
     */
    public Reply autoReplyDecision(String sender, boolean automated, boolean fromList, Instant now) {
        String from = normaliseAddress(sender);
        if (from.isEmpty()) return Reply.NO_SENDER;
        if (from.equals(mailbox)) return Reply.SELF;
        if (fromList) return Reply.LIST;
        if (automated || looksAutomated(from)) return Reply.AUTOMATED;
        if (!vacationEnabled) return Reply.OFF;
        if (!withinWindow(now)) return Reply.OUTSIDE_WINDOW;

        Instant last = autoReplied.get(from);
        if (last != null && !last.isBefore(periodStart(now))) return Reply.ALREADY_REPLIED;
        return Reply.SEND;
    }

    /**
     * The same decision, and the ledger is written when the answer is SEND.
     *
     * Deciding and recording have to be one call. Two calls with the reply in between is
     * a window in which a second message from the same sender takes the same decision,
     * and the second copy of a bounce-back is exactly the failure the rule exists to
     * stop. Callers persist the entity afterwards; inside one transaction this reads as
     * a claim.
     */
    public Reply claimAutoReply(String sender, boolean automated, boolean fromList, Instant now) {
        Reply decision = autoReplyDecision(sender, automated, fromList, now);
        if (decision == Reply.SEND) {
            forgetExpired(now);
            autoReplied.put(normaliseAddress(sender), now);
        }
        return decision;
    }

    /** Whether now falls inside the dates, treating either end as open when it is unset. */
    public boolean withinWindow(Instant now) {
        if (vacationFrom != null && now.isBefore(vacationFrom)) return false;
        return vacationTo == null || !now.isAfter(vacationTo);
    }

    /** Switched on and inside its dates, which is what the screen calls answering now. */
    public boolean vacationActive(Instant now) {
        return vacationEnabled && withinWindow(now);
    }

    /** The instant before which a previous reply no longer counts. */
    private Instant periodStart(Instant now) {
        return now.minus(vacationPeriodDays, ChronoUnit.DAYS);
    }

    /**
     * Drops entries older than the period, and then the oldest of whatever is left if
     * there is still too much of it. Both halves run on write rather than on read
     * because a read is the hot path and a stale entry can only ever make the rule more
     * cautious, never less.
     */
    public void forgetExpired(Instant now) {
        Instant cutoff = periodStart(now);
        autoReplied.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBefore(cutoff));
        if (autoReplied.size() < MAX_TRACKED_SENDERS) return;

        List<Map.Entry<String, Instant>> byAge = new ArrayList<>(autoReplied.entrySet());
        byAge.sort(Map.Entry.comparingByValue());
        int excess = autoReplied.size() - MAX_TRACKED_SENDERS + 1;
        for (int i = 0; i < excess && i < byAge.size(); i++) {
            autoReplied.remove(byAge.get(i).getKey());
        }
    }

    /** Switching the responder on again starts everybody period over. */
    public void clearAutoReplyLog() {
        autoReplied.clear();
    }

    /** How many senders are currently inside their period. Only the screen reads this. */
    public int autoRepliedCount() {
        return autoReplied.size();
    }

    /** When this sender last had an automatic reply, or null. Tests and the screen only. */
    public Instant lastAutoReply(String sender) {
        return autoReplied.get(normaliseAddress(sender));
    }

    /**
     * Addresses that no human reads, recognised from the local part alone.
     *
     * This is a supplement to the headers and never a replacement for them. It catches
     * the case the headers miss, which is a sender that sets none of them and is still a
     * robot, and it is a list of names rather than a pattern because noreply is a
     * convention and not a standard, so guessing more widely would start refusing replies
     * to real people whose address happens to contain the word.
     */
    public static boolean looksAutomated(String address) {
        String from = normaliseAddress(address);
        int at = from.indexOf('@');
        if (at <= 0) return true;
        String local = from.substring(0, at);
        if (ROBOT_LOCAL_PARTS.contains(local)) return true;
        // Variable Envelope Return Paths put the recipient inside the local part, so the
        // address is unique per message and answering it only reaches a bounce handler.
        return local.startsWith("bounce") || local.startsWith("owner-")
                || local.startsWith("mailer-daemon") || local.startsWith("noreply")
                || local.startsWith("no-reply");
    }

    public static String normaliseAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Validated writes
    // ------------------------------------------------------------------
    //
    // The setters clamp rather than throw. Every value here arrives from a select or a
    // number input on a screen this application drew, so an out of range one is a stale
    // tab or somebody with curl, and neither is worth a 400 that throws away the rest of
    // a form somebody has just filled in. An unknown reading pane becoming side is a
    // screen that draws; an exception is a screen that does not.

    public void setSignatureHtml(String cleanedHtml) {
        this.signatureHtml = cleanedHtml == null ? "" : cleanedHtml;
    }

    public void setSignatureOnNew(boolean on) { this.signatureOnNew = on; }

    public void setSignatureOnReply(boolean on) { this.signatureOnReply = on; }

    public void setVacationEnabled(boolean on) { this.vacationEnabled = on; }

    public void setVacationSubject(String subject) {
        this.vacationSubject = subject == null ? "" : subject.strip();
    }

    public void setVacationHtml(String cleanedHtml) {
        this.vacationHtml = cleanedHtml == null ? "" : cleanedHtml;
    }

    public void setVacationFrom(Instant from) { this.vacationFrom = from; }

    public void setVacationTo(Instant to) { this.vacationTo = to; }

    public void setVacationPeriodDays(int days) {
        this.vacationPeriodDays = Math.clamp(days, MIN_PERIOD_DAYS, MAX_PERIOD_DAYS);
    }

    public void setVacationSync(boolean serverSide, String note, Instant when) {
        this.vacationServerSide = serverSide;
        this.vacationServerNote = note == null ? "" : trim(note, 500);
        this.vacationSyncedAt = when;
    }

    public void setPreferHtml(boolean on) { this.preferHtml = on; }

    public void setLoadRemoteImages(boolean on) { this.loadRemoteImages = on; }

    public void setMessagesPerPage(int perPage) {
        this.messagesPerPage = Math.clamp(perPage, MIN_PER_PAGE, MAX_PER_PAGE);
    }

    public void setReadingPane(String pane) {
        String value = pane == null ? "" : pane.trim().toLowerCase(Locale.ROOT);
        this.readingPane = PANES.contains(value) ? value : PANE_SIDE;
    }

    public void setUndoSendSeconds(int seconds) {
        this.undoSendSeconds = Math.clamp(seconds, 0, MAX_UNDO_SECONDS);
    }

    public void setDefaultReply(String reply) {
        String value = reply == null ? "" : reply.trim().toLowerCase(Locale.ROOT);
        this.defaultReply = REPLIES.contains(value) ? value : REPLY_SENDER;
    }

    public void setRequestReadReceipt(boolean on) { this.requestReadReceipt = on; }

    public void setUpdatedAt(Instant when) { this.updatedAt = when; }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ------------------------------------------------------------------ reads

    public String getMailbox() { return mailbox; }

    public String getSignatureHtml() { return signatureHtml == null ? "" : signatureHtml; }

    public boolean isSignatureOnNew() { return signatureOnNew; }

    public boolean isSignatureOnReply() { return signatureOnReply; }

    public boolean isVacationEnabled() { return vacationEnabled; }

    public String getVacationSubject() { return vacationSubject == null ? "" : vacationSubject; }

    public String getVacationHtml() { return vacationHtml == null ? "" : vacationHtml; }

    public Instant getVacationFrom() { return vacationFrom; }

    public Instant getVacationTo() { return vacationTo; }

    public int getVacationPeriodDays() { return vacationPeriodDays; }

    public boolean isVacationServerSide() { return vacationServerSide; }

    public String getVacationServerNote() {
        return vacationServerNote == null ? "" : vacationServerNote;
    }

    public Instant getVacationSyncedAt() { return vacationSyncedAt; }

    public boolean isPreferHtml() { return preferHtml; }

    public boolean isLoadRemoteImages() { return loadRemoteImages; }

    public int getMessagesPerPage() { return messagesPerPage; }

    public String getReadingPane() { return readingPane; }

    public int getUndoSendSeconds() { return undoSendSeconds; }

    public String getDefaultReply() { return defaultReply; }

    public boolean isRequestReadReceipt() { return requestReadReceipt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
