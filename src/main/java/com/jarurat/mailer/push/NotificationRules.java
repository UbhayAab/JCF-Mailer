package com.jarurat.mailer.push;

import com.jarurat.mailer.models.MailboxSettings;
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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which arriving message is allowed to interrupt somebody, and which is only allowed to
 * be counted.
 *
 * THE KEY IS THE MAILBOX ADDRESS AND NOT AN APP_USER ID
 * -----------------------------------------------------
 * The same trap MailboxSettings documents at length, and for the same reason: a person
 * can reach the mailbox screen through MailboxAuthenticationProvider having proved a
 * Stalwart password and nothing else, so there is no app_user row anywhere to hang a
 * preference off. A foreign key onto one would either refuse the insert or file
 * everybody's rules under whichever console account happened to be signed in. The
 * address is also the thing the rule is genuinely about: whether a message to support@
 * deserves a sound is a property of support@ and of the work it does, not of whichever
 * of the three people who share it is holding a phone.
 *
 * The consequence, stated plainly because the settings screen has to state it too:
 * colleagues who share a mailbox share these rules, and the last to save wins. What is
 * NOT shared is the browser permission and the per-device subscription, which live on
 * the device and never in this row.
 *
 * VOLUME AND LOUDNESS ARE TWO DIALS, NOT ONE
 * ------------------------------------------
 * Every mail client that survived contact with real inboxes separates which mail
 * produces a notification from how loudly it does so. Gmail calls it priority, Apple
 * calls it VIP, Outlook calls it Focused, Spark calls it Smart. The reason is the
 * failure this whole file exists to avoid: a mailbox that beeps for everything gets its
 * notifications switched off within a week, and then the hospital referral is lost along
 * with the newsletters. So there are three lanes and only one of them makes a sound.
 *
 * A shared alias makes that argument sharper rather than softer. One message to
 * support@ under an all-mail-loud rule interrupts three people, two of whom must not act
 * on it and none of whom can tell from the notification which of them it is for.
 *
 * WHAT THIS CLASS IS NOT
 * ----------------------
 * It decides a lane and nothing else. It draws no notification, holds no queue, talks to
 * no mail server and knows nothing about transport, so the same answer is correct
 * whether the notification is painted by the open tab through MailPollApi or pushed to a
 * sleeping phone later. Everything it needs arrives in an Arrival, which makes it a pure
 * function of its inputs and therefore testable, which for a rule whose failure mode is
 * "everyone turned notifications off" is the whole point.
 */
@Entity
@Table(name = "notification_rules")
public class NotificationRules {

    // ------------------------------------------------------------------ the lanes

    /**
     * How much of a person's attention a message has earned.
     *
     * The web platform gives exactly one lever for the top two, and it is
     * showNotification's silent flag, which suppresses sound and vibration regardless of
     * device settings. DELIVER is that flag set; INTERRUPT is that flag left alone so
     * iOS Focus and Android Do Not Disturb still get their say.
     */
    public enum Lane {
        /** Breaks through. Makes a sound. Rare by construction, or it is worth nothing. */
        INTERRUPT("A"),
        /** Appears in full, silently. This is where a high volume of mail is allowed to live. */
        DELIVER("B"),
        /** No notification at all. The tab title, the favicon and the app badge still move. */
        COUNT("C");

        private final String code;

        Lane(String code) {
            this.code = code;
        }

        /**
         * The single letter the transport side already speaks.
         *
         * PushNotification carries its lane as the string A or B, because that is what
         * ends up in an encrypted payload where every byte counts against a 3993 byte
         * ceiling. Naming the same three lanes twice is how two halves of one feature
         * drift, so the mapping is written here, once, next to the definition.
         */
        public String code() {
            return code;
        }
    }

    /**
     * Why the lane came out the way it did.
     *
     * Carried out of the decision rather than discarded because two different readers
     * need it: the tests, which would otherwise assert the right answer for the wrong
     * reason and keep passing after the reason breaks, and the settings screen, which
     * can only honestly explain a rule if it can say which rule fired.
     */
    public enum Reason {
        /** Another device, or this one, has already read it. Not an event. */
        ALREADY_SEEN,
        /** Junk, Spam or Trash. Never notified, at any setting. See NEVER_NOTIFIED. */
        QUARANTINED_FOLDER,
        /** The mailbox wrote to itself. Never report a person's own action back to them. */
        OWN_MESSAGE,
        /** This folder is set to Nothing. */
        FOLDER_SILENT,
        /** The sender was muted, and the mute has not run out. */
        MUTED_SENDER,
        /** A robot: List-Unsubscribe, Precedence bulk, Auto-Submitted, or a noreply address. */
        AUTOMATED,
        /** A sender or domain on the VIP list. */
        VIP,
        /** A thread this mailbox has sent into, and is therefore waiting on. */
        WATCHED_THREAD,
        /** The mailbox address is in To, on a message with few enough recipients to mean it. */
        DIRECT_TO_ME,
        /** Cc, or a To line long enough that being on it says nothing. */
        NOT_DIRECT,
        /** Ordinary human mail, and the folder is set to notify for all of it. */
        HUMAN_MAIL,
        /** The folder is set to VIPs only and this sender is not one. */
        NOT_A_VIP,
        /** A message this mailbox tried to send and could not. Never a routine event. */
        SEND_FAILED
    }

    // ------------------------------------------------------------------ the levels

    /** Every human message in this folder may interrupt. */
    public static final String LEVEL_EVERYTHING = "everything";

    /**
     * Mail addressed to this mailbox in To, VIPs, and threads it is waiting on may
     * interrupt. Everything else human is still delivered, silently and in full.
     */
    public static final String LEVEL_DIRECT = "direct";

    /** Only VIPs produce anything at all. */
    public static final String LEVEL_VIP = "vip";

    /** Nothing from this folder produces anything. */
    public static final String LEVEL_NOTHING = "nothing";

    private static final Set<String> LEVELS =
            Set.of(LEVEL_EVERYTHING, LEVEL_DIRECT, LEVEL_VIP, LEVEL_NOTHING);

    /**
     * Folders that are never notified, whatever anybody sets.
     *
     * Notifying on spam is notifying on phishing, and it hands the attacker the lock
     * screen of a mailbox that receives patient mail. A spoofed "Tata Memorial: urgent"
     * at 22:00 is exactly the delivery the sender paid for, and a VIP rule cannot rescue
     * it because naming a VIP is the whole technique. Trash and Drafts are here for the
     * duller reason that a message in either arrived there by somebody's own action.
     *
     * This is deliberately not a setting. A switch labelled "notify me about spam" is a
     * switch somebody will eventually turn on.
     */
    static final Set<String> NEVER_NOTIFIED = Set.of("junk", "spam", "trash", "drafts");

    /** The role every mailbox has, and the only one that notifies out of the box. */
    static final String INBOX = "inbox";

    /**
     * How many recipients a message may carry before being in its To line stops meaning
     * anything.
     *
     * This is the number that decides whether people keep notifications switched on. A
     * blast to sixty addresses with this mailbox among them is not somebody choosing
     * you, it is a mail merge that skipped Bcc, and treating it as direct is how the
     * Direct setting quietly becomes the Everything setting. Twelve is above any real
     * thread this organisation runs - a hospital, a family, two case workers and the
     * finance desk is nine - and well below any list worth the name.
     */
    static final int MAX_DIRECT_RECIPIENTS = 12;

    /** A VIP list longer than this is not a VIP list. */
    public static final int MAX_VIPS = 100;

    /** How long muting a sender lasts before it has to be asked for again. */
    public static final int MUTE_DAYS = 30;

    // ------------------------------------------------------------------ the row

    /** Lower cased, always. The address is the identity, so it has to compare exactly. */
    @Id
    @Column(name = "mailbox", length = 320, nullable = false)
    private String mailbox;

    /**
     * Folder role to level. Absent means the default for that role, which is Direct for
     * the Inbox and Nothing everywhere else.
     *
     * Keyed on the JMAP role rather than on the folder id because an id is per account
     * and would break the moment somebody's mailbox is rebuilt, and because a rule about
     * the Archive is a rule about the idea of an archive.
     *
     * Eager for the same reason MailboxSettings' reply ledger is: this is read on the
     * same path that reads the row, it is a handful of entries rather than a table scan
     * waiting to happen, and lazy here would only produce a LazyInitializationException
     * the first time somebody evaluated a rule outside a transaction.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "notification_folder_level",
            joinColumns = @JoinColumn(name = "mailbox"))
    @MapKeyColumn(name = "folder_role", length = 64)
    @Column(name = "notify_level", length = 16, nullable = false)
    private Map<String, String> folderLevels = new HashMap<>();

    /**
     * A sender address, or a whole domain written with a leading at sign, to whether it
     * may break through quiet hours.
     *
     * The break-through flag is per VIP and defaults to off, including for VIPs added
     * later. It is the only route to a sound inside the quiet window, and being entirely
     * opt-in per sender is the reason a person is willing to leave quiet hours on at all.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "notification_vip",
            joinColumns = @JoinColumn(name = "mailbox"))
    @MapKeyColumn(name = "sender", length = 320)
    @Column(name = "quiet_break", nullable = false)
    private Map<String, Boolean> vips = new HashMap<>();

    /**
     * Senders sent to the counting lane, and the instant the mute runs out.
     *
     * Expiring rather than permanent because a permanent mute is a filter somebody sets
     * once and then spends a year not understanding. Expired entries are dropped on
     * every write, so this cannot grow without limit.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "notification_muted",
            joinColumns = @JoinColumn(name = "mailbox"))
    @MapKeyColumn(name = "sender", length = 320)
    @Column(name = "muted_until", nullable = false)
    private Map<String, Instant> muted = new HashMap<>();

    // ------------------------------------------------------------------ quiet hours

    @Column(name = "quiet_enabled", nullable = false)
    private boolean quietEnabled = true;

    /**
     * The same three numbers Journey carries, on purpose.
     *
     * JourneyEngine.applyQuietHours already establishes 21:00 to 08:00 Asia/Kolkata as
     * this application's one quiet convention, including the start greater than end test
     * that reads it as an overnight window and the start equals end case that means no
     * window at all. A second convention would be a second thing to get wrong and a
     * second answer to the question "when is this place asleep", so these are the same
     * defaults and quiet() below is the same test. There is deliberately no separate
     * weekend rule, for the same reason.
     */
    @Column(name = "quiet_start_hour", nullable = false)
    private int quietStartHour = 21;

    @Column(name = "quiet_end_hour", nullable = false)
    private int quietEndHour = 8;

    @Column(name = "quiet_zone", length = 64, nullable = false)
    private String zoneId = "Asia/Kolkata";

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected NotificationRules() {
    }

    public NotificationRules(String mailbox) {
        this.mailbox = MailboxSettings.normaliseAddress(mailbox);
    }

    // ==================================================================
    // What arrived
    // ==================================================================

    /**
     * One message, reduced to the facts a lane depends on.
     *
     * A record rather than a handle on a JMAP object because the caller may be the poll
     * path, a push path built later, or a test, and none of them should have to agree on
     * a message type to agree on a rule. automated and fromList are the caller's reading
     * of the headers rather than something derivable here, exactly as
     * MailboxSettings.autoReplyDecision takes them: RFC 3834 puts the first in
     * Auto-Submitted and the mailing list convention puts the second in List-Id,
     * List-Unsubscribe or Precedence.
     *
     * The subject is not here. Nothing in these rules reads it, and a rule that matched
     * on subject text would be a filter language, which is the next thing to build and
     * not this one.
     */
    public record Arrival(String from,
                          Collection<String> to,
                          Collection<String> cc,
                          String folderRole,
                          boolean seen,
                          boolean automated,
                          boolean fromList,
                          boolean watchedThread) {

        public Arrival {
            from = MailboxSettings.normaliseAddress(from);
            to = to == null ? List.of() : List.copyOf(to);
            cc = cc == null ? List.of() : List.copyOf(cc);
            folderRole = folderRole == null ? "" : folderRole.trim().toLowerCase(Locale.ROOT);
        }

        /** Everybody named on the message. Bcc is invisible to us by definition. */
        int recipientCount() {
            return to.size() + cc.size();
        }
    }

    /**
     * The answer, with the reason it came out that way and whether quiet hours took the
     * sound off it.
     *
     * quietMuted is separate from the lane rather than folded into it because the two
     * facts are different and the screen says different things about them. A message
     * that landed in DELIVER because it was a Cc is a message the rules judged ordinary;
     * a message that landed in DELIVER at 02:40 having been judged an interruption is a
     * message that will be waiting, in full, when somebody wakes up.
     */
    public record Decision(Lane lane, Reason reason, boolean quietMuted) {

        public boolean interrupts() {
            return lane == Lane.INTERRUPT;
        }

        /** Whether anything at all is shown, which is the question the poll path asks. */
        public boolean notifies() {
            return lane != Lane.COUNT;
        }
    }

    // ==================================================================
    // The rule
    // ==================================================================

    /**
     * Which lane this message earns.
     *
     * The order of the tests is the argument. Each one is either a thing that must never
     * notify whatever anybody set, or a rule that has to be checked before a broader one
     * can be trusted.
     *
     * Seen first, because a message another device already read is not an event and
     * announcing it is how a two-device person learns to ignore this app. Quarantine
     * next, because it is the one refusal no setting may overturn. Own mail next,
     * because the echo of a person's own action is never news. Only then the folder
     * level, which is the first thing a person actually chose.
     *
     * VIP is resolved before the robot test and before the mute, and that ordering is
     * deliberate: a person who put an address on the VIP list has made a statement more
     * specific than any inference we can draw from a header, and a payroll system that
     * sends from a noreply address is exactly the case where they are right and we are
     * wrong.
     */
    public Decision decide(Arrival arrival, Instant now) {
        if (arrival.seen()) return count(Reason.ALREADY_SEEN);
        if (NEVER_NOTIFIED.contains(arrival.folderRole())) return count(Reason.QUARANTINED_FOLDER);
        if (!arrival.from().isEmpty() && arrival.from().equals(mailbox)) {
            return count(Reason.OWN_MESSAGE);
        }

        String level = levelFor(arrival.folderRole());
        if (LEVEL_NOTHING.equals(level)) return count(Reason.FOLDER_SILENT);

        boolean vip = isVip(arrival.from());

        if (!vip && isMuted(arrival.from(), now)) return count(Reason.MUTED_SENDER);
        if (!vip && looksAutomated(arrival)) return count(Reason.AUTOMATED);

        if (vip) return interrupt(Reason.VIP, now, breaksThroughQuiet(arrival.from()));
        if (LEVEL_VIP.equals(level)) return count(Reason.NOT_A_VIP);

        // A thread this mailbox has already sent into is one it is waiting on, which is
        // the strongest signal of wanting an answer that exists without anybody
        // configuring anything. Whether a thread counts as watched is the caller's to
        // decide, so that the fourteen day window lives with the thread data and not here.
        if (arrival.watchedThread()) return interrupt(Reason.WATCHED_THREAD, now, false);

        boolean direct = addressedDirectly(arrival);
        if (LEVEL_EVERYTHING.equals(level)) {
            return interrupt(direct ? Reason.DIRECT_TO_ME : Reason.HUMAN_MAIL, now, false);
        }
        // LEVEL_DIRECT, the default. Note that this is the only branch that produces
        // DELIVER from a rule rather than from quiet hours, and it is where the bulk of
        // an ordinary day's mail lands: shown in full, silently, nothing hidden.
        return direct
                ? interrupt(Reason.DIRECT_TO_ME, now, false)
                : new Decision(Lane.DELIVER, Reason.NOT_DIRECT, false);
    }

    /**
     * A message this mailbox tried to send and could not.
     *
     * It is here rather than left to the caller so that there is one convention for what
     * quiet hours do, and it bypasses the folder levels entirely because it is not mail
     * arriving. A person believes a message went and it did not; there is no other
     * channel that will tell them, and nobody has ever resented being told. A scheduled
     * send that fails at 06:00 with nobody watching is this application's worst current
     * silent failure.
     *
     * It still obeys quiet hours, because a send that failed at 02:00 cannot usefully be
     * retried at 02:00 and the record is waiting either way.
     */
    public Decision decideSendFailure(Instant now) {
        return interrupt(Reason.SEND_FAILED, now, false);
    }

    /**
     * Interrupt, unless the clock says the house is asleep.
     *
     * QUIET HOURS MUTE. THEY DO NOT HOLD, DELAY, BATCH OR DROP.
     *
     * This is the one place where copying JourneyEngine.applyQuietHours wholesale would
     * be wrong, so the difference is written down. That method shifts an outgoing send
     * forward, and it is right to, because the thing being scheduled has not happened yet
     * and the recipient's attention is the scarce resource. A notification is not a send.
     * The mail has already arrived. Holding the notification does not move the event, it
     * only moves our report of it, and the report is the only thing the person has.
     *
     * Someone who wakes at 03:00, checks their phone and sees nothing concludes that
     * nothing arrived. For a mailbox that receives patient mail that is a false negative
     * we manufactured, and it is not recoverable, because they went back to sleep.
     * Dropping it is worse again for the same reason and buys nothing extra.
     *
     * So the notification is shown immediately, in full, timestamped when the mail
     * actually arrived, with the sound taken off it. That is what Do Not Disturb does,
     * what iOS Focus does, and what the silent flag was put in the specification for.
     * Nothing is replayed at 08:00, because it is all already there.
     */
    private Decision interrupt(Reason reason, Instant now, boolean breakThrough) {
        if (!breakThrough && quiet(now)) return new Decision(Lane.DELIVER, reason, true);
        return new Decision(Lane.INTERRUPT, reason, false);
    }

    private static Decision count(Reason reason) {
        return new Decision(Lane.COUNT, reason, false);
    }

    /**
     * Whether now falls inside the quiet window.
     *
     * The overnight test is lifted from JourneyEngine.applyQuietHours rather than
     * rewritten, including the start equals end case that means there is no window, so
     * the two cannot drift into disagreeing about when 21:00 to 08:00 is. An unparseable
     * zone falls back to the system default rather than throwing: a bad zone string
     * should cost a wrong hour, never a notification path that dies.
     */
    public boolean quiet(Instant now) {
        if (!quietEnabled || now == null) return false;
        if (quietStartHour == quietEndHour) return false;

        ZoneId zone;
        try {
            zone = ZoneId.of(getZoneId());
        } catch (RuntimeException e) {
            zone = ZoneId.systemDefault();
        }
        int hour = now.atZone(zone).getHour();
        boolean overnight = quietStartHour > quietEndHour;      // e.g. 21:00 to 08:00
        return overnight
                ? (hour >= quietStartHour || hour < quietEndHour)
                : (hour >= quietStartHour && hour < quietEndHour);
    }

    /**
     * Whether the mailbox is on the To line of a message small enough for that to mean
     * something.
     *
     * To rather than Cc is the whole distinction. Cc is the convention for "you may want
     * to know", and treating it as "answer me" is what turns a shared alias into a
     * pager. The recipient count is the other half: being one of sixty addresses in a To
     * line is a mail merge, not a choice, and without this test the Direct setting
     * degrades into the Everything setting on exactly the mail people most resent.
     */
    boolean addressedDirectly(Arrival arrival) {
        if (arrival.recipientCount() > MAX_DIRECT_RECIPIENTS) return false;
        for (String address : arrival.to()) {
            if (MailboxSettings.normaliseAddress(address).equals(mailbox)) return true;
        }
        return false;
    }

    /**
     * A robot, by the headers the caller read or by the address itself.
     *
     * MailboxSettings.looksAutomated is reused rather than a second list of local parts
     * being written here. That list is the one the out of office rule already trusts, it
     * is already tested, and two copies of it would drift the first time somebody added a
     * name to one of them.
     */
    private static boolean looksAutomated(Arrival arrival) {
        return arrival.automated() || arrival.fromList()
                || MailboxSettings.looksAutomated(arrival.from());
    }

    // ==================================================================
    // The lists
    // ==================================================================

    /** The level in force for a folder role, falling back to the defaults. */
    public String levelFor(String folderRole) {
        String role = folderRole == null ? "" : folderRole.trim().toLowerCase(Locale.ROOT);
        String set = folderLevels.get(role);
        if (set != null && LEVELS.contains(set)) return set;
        return defaultLevelFor(role);
    }

    /**
     * The Inbox notifies out of the box and nothing else does.
     *
     * The argument for a default that fires on day one without configuration, rather
     * than a VIP-only default that is more principled and fires never: a fifteen person
     * foundation installs this, grants the permission, sees nothing for a fortnight
     * because nobody has added a VIP, concludes it is broken, and never goes back. A
     * default that needs configuration to produce its first notification is a default
     * that never fires, and the browser permission it spent is not refundable.
     */
    static String defaultLevelFor(String role) {
        if (NEVER_NOTIFIED.contains(role)) return LEVEL_NOTHING;
        return INBOX.equals(role) ? LEVEL_DIRECT : LEVEL_NOTHING;
    }

    /** Whether this sender, or their whole domain, is on the VIP list. */
    public boolean isVip(String address) {
        return vipEntry(address) != null;
    }

    /** Whether this VIP may make a sound inside quiet hours. Off unless asked for. */
    public boolean breaksThroughQuiet(String address) {
        String key = vipEntry(address);
        return key != null && Boolean.TRUE.equals(vips.get(key));
    }

    /**
     * The VIP key that matches, or null.
     *
     * A key beginning with an at sign is a whole domain, which is how a hospital gets on
     * the list without somebody entering forty consultants one at a time. The exact
     * address is tested first so that a single address can be listed with break-through
     * on while its domain is listed without.
     */
    private String vipEntry(String address) {
        String from = MailboxSettings.normaliseAddress(address);
        if (from.isEmpty()) return null;
        if (vips.containsKey(from)) return from;

        int at = from.indexOf('@');
        if (at < 0) return null;
        String domain = from.substring(at);
        return vips.containsKey(domain) ? domain : null;
    }

    public boolean isMuted(String address, Instant now) {
        Instant until = muted.get(MailboxSettings.normaliseAddress(address));
        return until != null && now != null && until.isAfter(now);
    }

    // ==================================================================
    // Validated writes
    // ==================================================================
    //
    // The setters clamp and ignore rather than throw, the same way MailboxSettings does
    // and for the same reason: every value arrives from a select this application drew,
    // so an out of range one is a stale tab or somebody with curl, and neither is worth
    // throwing away the rest of a form somebody has just filled in. An unknown level
    // becoming the default is a screen that draws; an exception is a screen that does not.

    /**
     * Sets one folder's level. A quarantined folder refuses quietly.
     *
     * Refusing rather than throwing because the only way to reach this with junk is a
     * stale tab or a hand-made request, and in both cases the right answer is that the
     * rule did not change. The screen never offers the control in the first place.
     */
    public void setLevel(String folderRole, String level) {
        String role = folderRole == null ? "" : folderRole.trim().toLowerCase(Locale.ROOT);
        String value = level == null ? "" : level.trim().toLowerCase(Locale.ROOT);
        if (role.isEmpty() || !LEVELS.contains(value)) return;
        if (NEVER_NOTIFIED.contains(role)) return;
        folderLevels.put(role, value);
    }

    /**
     * Replaces the whole VIP list.
     *
     * A whole-list write rather than add and remove calls, because the settings sheet
     * always knows the complete list and a two-verb API is how a list gets out of step
     * with the screen showing it. Entries past the cap are dropped rather than refused,
     * for the same reason the setters clamp.
     */
    public void setVips(Map<String, Boolean> wanted) {
        vips.clear();
        if (wanted == null) return;
        for (Map.Entry<String, Boolean> entry : wanted.entrySet()) {
            if (vips.size() >= MAX_VIPS) break;
            String key = normaliseVip(entry.getKey());
            if (key == null) continue;
            vips.put(key, Boolean.TRUE.equals(entry.getValue()));
        }
    }

    /**
     * An address, or a domain written with a leading at sign, or null if it is neither.
     *
     * A bare domain typed without the at sign is read as a domain rather than refused,
     * because that is what somebody typing tmc.gov.in into a box labelled sender or
     * domain means, and refusing it would be a validation error nobody learns anything
     * from.
     */
    static String normaliseVip(String raw) {
        String value = MailboxSettings.normaliseAddress(raw);
        if (value.isEmpty() || value.indexOf(' ') >= 0) return null;
        int at = value.indexOf('@');
        if (at < 0) return value.contains(".") ? "@" + value : null;
        if (at == 0) return value.length() > 1 && value.contains(".") ? value : null;
        return at == value.lastIndexOf('@') && at < value.length() - 1 ? value : null;
    }

    /** Sends a sender to the counting lane for MUTE_DAYS. */
    public void mute(String address, Instant now) {
        String key = MailboxSettings.normaliseAddress(address);
        if (key.isEmpty() || now == null) return;
        forgetExpiredMutes(now);
        muted.put(key, now.plus(MUTE_DAYS, ChronoUnit.DAYS));
    }

    public void unmute(String address) {
        muted.remove(MailboxSettings.normaliseAddress(address));
    }

    /** Dropped on write rather than on read, so the map cannot grow without limit. */
    public void forgetExpiredMutes(Instant now) {
        if (now == null) return;
        muted.entrySet().removeIf(e -> e.getValue() == null || !e.getValue().isAfter(now));
    }

    public void setQuietEnabled(boolean on) {
        this.quietEnabled = on;
    }

    public void setQuietHours(int startHour, int endHour) {
        this.quietStartHour = Math.clamp(startHour, 0, 23);
        this.quietEndHour = Math.clamp(endHour, 0, 23);
    }

    /** An unknown zone is kept out of the row rather than stored and failed on later. */
    public void setZoneId(String zone) {
        String value = zone == null ? "" : zone.trim();
        if (value.isEmpty()) return;
        try {
            ZoneId.of(value);
        } catch (RuntimeException e) {
            return;
        }
        this.zoneId = value;
    }

    public void setUpdatedAt(Instant when) {
        this.updatedAt = when;
    }

    // ------------------------------------------------------------------ reads

    public String getMailbox() { return mailbox; }

    /** A copy, because the caller is a JSON shape and not a second owner of the row. */
    public Map<String, String> getFolderLevels() { return new LinkedHashMap<>(folderLevels); }

    public Map<String, Boolean> getVips() { return new LinkedHashMap<>(vips); }

    public Map<String, Instant> getMuted() { return new LinkedHashMap<>(muted); }

    /** The VIP keys, sorted, so the screen draws the same order every time. */
    public List<String> vipList() {
        List<String> out = new ArrayList<>(vips.keySet());
        out.sort(String::compareTo);
        return out;
    }

    public boolean isQuietEnabled() { return quietEnabled; }

    public int getQuietStartHour() { return quietStartHour; }

    public int getQuietEndHour() { return quietEndHour; }

    public String getZoneId() {
        return zoneId == null || zoneId.isBlank() ? "Asia/Kolkata" : zoneId;
    }

    public Instant getUpdatedAt() { return updatedAt; }
}
