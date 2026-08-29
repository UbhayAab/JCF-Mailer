package com.jarurat.mailer.push;

import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.models.MailboxSettings;
import com.jarurat.mailer.services.AuditService;
import com.jarurat.mailer.webmail.MailboxAccess;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The screen behind NotificationRules: read the rules for the open mailbox, and save
 * them.
 *
 * THE MAILBOX IS NEVER A PARAMETER. Both methods resolve it through MailboxAccess from
 * the session pin, exactly as every other endpoint under /api/mail does, so there is no
 * rule set a signed-in user can read or write except the one belonging to the mailbox
 * they produced a password for. That matters more here than it does for a signature: the
 * VIP list is a short account of who somebody in this organisation is talking to, and on
 * a mailbox that receives patient mail that is not a neutral thing to leak.
 *
 * WHY THIS TALKS TO THE ENTITY MANAGER RATHER THAN A REPOSITORY
 * ------------------------------------------------------------
 * Every other persistent type here has a Spring Data interface under repositories, and
 * this one should too. It does not because this phase owns exactly two Java files and a
 * repository would be a third, and a rule that has to be argued about is better than a
 * file quietly added to somebody else's package while they are working in it. find and
 * merge against a single primary key is what a repository would generate anyway, so the
 * migration is a delete and a rename rather than a rewrite. Stated here rather than
 * hidden, so whoever consolidates knows it was a scope decision and not a preference.
 *
 * ABSENT AND EMPTY ARE DIFFERENT, EVERYWHERE
 * ------------------------------------------
 * Every parameter is optional and an absent one leaves the stored value alone, which is
 * the same contract MailSettingsApi holds to and for the same reason: a sheet that
 * changed one control should be able to post one field. The VIP list is the case that
 * makes this sharp, because an empty list is a meaningful thing to want. So the list is
 * only rewritten when the marker parameter vips is present, and never merely because no
 * vip parameter arrived.
 */
@RestController
@RequestMapping("/api/mail/notify/rules")
public class NotificationRuleApi {

    /** JMAP UTCDate: seconds, always Z, never an offset. Same format as the rest of /api/mail. */
    private static final DateTimeFormatter UTC_DATE = DateTimeFormatter.ISO_INSTANT;

    /**
     * The folder roles the screen offers a control for.
     *
     * Deliberately short. Junk, Spam, Trash and Drafts are in NotificationRules'
     * never-notified set and get a sentence rather than a select, because a control that
     * cannot change anything is worse than no control. Sent is left out because a
     * message from this mailbox to itself is already refused as OWN_MESSAGE, so the
     * select would have exactly one reachable outcome.
     */
    private static final List<String> OFFERED_ROLES = List.of("inbox", "archive");

    /** What a level select may contain, in the order the screen shows them. */
    private static final List<String> LEVEL_ORDER = List.of(
            NotificationRules.LEVEL_EVERYTHING,
            NotificationRules.LEVEL_DIRECT,
            NotificationRules.LEVEL_VIP,
            NotificationRules.LEVEL_NOTHING);

    /**
     * A per-request cap on the folder levels one save may carry.
     *
     * Not a security boundary, just a bound. A hand-made request naming ten thousand
     * folder roles would otherwise write ten thousand rows nothing will ever read,
     * because every role the screen can reach is in OFFERED_ROLES.
     */
    private static final int MAX_FOLDER_WRITES = 32;

    private final MailboxAccess mailbox;
    private final AuditService audit;

    @PersistenceContext
    private EntityManager em;

    public NotificationRuleApi(MailboxAccess mailbox, AuditService audit) {
        this.mailbox = mailbox;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ read

    /**
     * Everything the notifications section of the settings sheet needs, in one call.
     *
     * The effective folder levels are sent rather than only the stored ones, so the
     * screen never has to know what the default for a role is. Two places holding an
     * opinion about that is how a select ends up showing Nothing for a folder that is
     * actually notifying.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('MAIL_READ')")
    @Transactional(readOnly = true)
    public Map<String, Object> read(Authentication auth, HttpSession session) {
        String address = mailbox.require(auth, session);
        return asJson(load(address), Instant.now());
    }

    // ------------------------------------------------------------------ write

    /**
     * Saves whatever the request mentions and leaves the rest alone.
     *
     * A MultiValueMap rather than a list of named parameters because two of the things
     * being written are lists, and a repeated form field is how a browser sends a list
     * without anybody having to agree on a separator character that will eventually turn
     * up inside an address.
     *
     * The wire shape:
     *   folder.inbox=direct       one per folder role being changed
     *   quietEnabled=true         quietStartHour=21  quietEndHour=8  zone=Asia/Kolkata
     *   vips=1                    the marker: the VIP list in this request is the whole list
     *   vip=anand@tmc.gov.in|1    the pipe carries the quiet-hours break-through flag
     *   mute=newsletter@x.example unmute=someone@y.example
     */
    @PostMapping
    @PreAuthorize("hasAuthority('MAIL_READ')")
    @Transactional
    public Map<String, Object> save(Authentication auth, HttpSession session,
                                    @RequestParam MultiValueMap<String, String> params) {
        String address = mailbox.require(auth, session);
        Instant now = Instant.now();
        NotificationRules rules = load(address);

        int folderWrites = 0;
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            if (!entry.getKey().startsWith("folder.")) continue;
            if (++folderWrites > MAX_FOLDER_WRITES) break;
            rules.setLevel(entry.getKey().substring("folder.".length()), first(entry.getValue()));
        }

        String quietOn = one(params, "quietEnabled");
        if (quietOn != null) rules.setQuietEnabled(flag(quietOn));

        String start = one(params, "quietStartHour");
        String end = one(params, "quietEndHour");
        if (start != null || end != null) {
            // Both go through one call because a half-written window is not a state this
            // row should ever hold: a start of 21 saved against an unchanged end of 21
            // means no quiet hours at all, which is the opposite of what somebody moving
            // one of the two selects is asking for.
            rules.setQuietHours(number(start, rules.getQuietStartHour()),
                    number(end, rules.getQuietEndHour()));
        }

        String zone = one(params, "zone");
        if (zone != null) rules.setZoneId(zone);

        if (params.containsKey("vips")) rules.setVips(readVips(params.get("vip")));

        for (String muted : params.getOrDefault("mute", List.of())) rules.mute(muted, now);
        for (String unmuted : params.getOrDefault("unmute", List.of())) rules.unmute(unmuted);
        rules.forgetExpiredMutes(now);

        rules.setUpdatedAt(now);
        NotificationRules saved = em.merge(rules);

        // Worth a line in the audit log for the reason the class comment gives: these
        // rules belong to the address, so a colleague who shares the mailbox can change
        // what interrupts everybody else, and the only way to answer "why did my phone
        // stop ringing" afterwards is to have written down that it changed and when.
        audit.record("MAIL_NOTIFY_RULES_SAVED", address,
                "inbox " + saved.levelFor(NotificationRules.INBOX)
                        + ", quiet hours " + (saved.isQuietEnabled()
                                ? saved.getQuietStartHour() + ":00 to " + saved.getQuietEndHour()
                                        + ":00 " + saved.getZoneId()
                                : "off")
                        + ", " + saved.vipList().size() + " VIPs");

        return asJson(saved, now);
    }

    // ------------------------------------------------------------------ shapes

    /**
     * The address and its break-through flag, read off the repeated vip parameters.
     *
     * A LinkedHashMap so the order the screen sent survives into the cap in setVips: if
     * somebody does pass more than a hundred, the ones that survive are the ones at the
     * top of their list rather than an arbitrary hash order.
     */
    private static Map<String, Boolean> readVips(List<String> raw) {
        Map<String, Boolean> wanted = new LinkedHashMap<>();
        if (raw == null) return wanted;
        for (String value : raw) {
            if (value == null || value.isBlank()) continue;
            int bar = value.indexOf('|');
            String address = bar < 0 ? value : value.substring(0, bar);
            boolean breakThrough = bar >= 0 && flag(value.substring(bar + 1));
            String key = NotificationRules.normaliseVip(address);
            if (key == null) {
                throw new MailException(MailException.Kind.PROTOCOL,
                        "\"" + address.trim() + "\" is not an address or a domain. "
                                + "Write anand@tmc.gov.in for one person, or @tmc.gov.in "
                                + "for everybody there.");
            }
            wanted.put(key, breakThrough);
        }
        if (wanted.size() > NotificationRules.MAX_VIPS) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "That is " + wanted.size() + " VIPs and the limit is "
                            + NotificationRules.MAX_VIPS
                            + ". A list that long is not a list of people who matter more.");
        }
        return wanted;
    }

    private Map<String, Object> asJson(NotificationRules rules, Instant now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mailbox", rules.getMailbox());

        out.put("levels", LEVEL_ORDER);
        out.put("folders", effectiveLevels(rules));
        out.put("neverNotified", new ArrayList<>(new TreeSet<>(NotificationRules.NEVER_NOTIFIED)));

        out.put("quietEnabled", rules.isQuietEnabled());
        out.put("quietStartHour", rules.getQuietStartHour());
        out.put("quietEndHour", rules.getQuietEndHour());
        out.put("zone", rules.getZoneId());
        out.put("quietNow", rules.quiet(now));
        // What quiet hours DO, sent as a fact rather than left to the screen to phrase,
        // because it is the one thing about this feature a person has to be told and
        // getting it wrong on one surface would make the other one a lie.
        out.put("quietSilences", true);
        out.put("quietHolds", false);

        Map<String, Boolean> stored = rules.getVips();
        List<Map<String, Object>> vips = new ArrayList<>();
        for (String key : rules.vipList()) {
            Map<String, Object> vip = new LinkedHashMap<>();
            vip.put("address", key);
            vip.put("domain", key.startsWith("@"));
            // Read off the stored map rather than through breaksThroughQuiet, because
            // that method takes a sender address and matches it against the list, and a
            // domain entry is not an address anybody could ever send from.
            vip.put("quietBreak", Boolean.TRUE.equals(stored.get(key)));
            vips.add(vip);
        }
        out.put("vips", vips);
        out.put("maxVips", NotificationRules.MAX_VIPS);

        List<Map<String, Object>> muted = new ArrayList<>();
        for (Map.Entry<String, Instant> entry : new TreeMap<>(rules.getMuted()).entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("address", entry.getKey());
            row.put("until", iso(entry.getValue()));
            muted.add(row);
        }
        out.put("muted", muted);
        out.put("muteDays", NotificationRules.MUTE_DAYS);

        out.put("maxDirectRecipients", NotificationRules.MAX_DIRECT_RECIPIENTS);
        // The sentence the screen has to print under the level control. It is a property
        // of how these rows are keyed, so it is answered here rather than assumed there.
        out.put("sharedWithMailbox", true);
        out.put("updatedAt", iso(rules.getUpdatedAt()));
        return out;
    }

    /** The offered roles plus anything already stored, each with the level actually in force. */
    private static Map<String, String> effectiveLevels(NotificationRules rules) {
        Set<String> roles = new LinkedHashSet<>(OFFERED_ROLES);
        roles.addAll(rules.getFolderLevels().keySet());

        Map<String, String> out = new LinkedHashMap<>();
        for (String role : roles) {
            if (NotificationRules.NEVER_NOTIFIED.contains(role)) continue;
            out.put(role, rules.levelFor(role));
        }
        return out;
    }

    /**
     * The stored row, or a fresh one carrying the defaults.
     *
     * Never persisted on a read. A mailbox somebody has only ever looked at has no row,
     * and creating one on a GET would mean the first poll of every mailbox in the
     * organisation writes to the database to record that nothing was chosen.
     */
    private NotificationRules load(String address) {
        NotificationRules found = em.find(NotificationRules.class,
                MailboxSettings.normaliseAddress(address));
        return found == null ? new NotificationRules(address) : found;
    }

    // ------------------------------------------------------------------ parsing

    private static String one(MultiValueMap<String, String> params, String key) {
        List<String> values = params.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    /**
     * Checkbox truth, accepting what a form and what fetch each send.
     *
     * The same two-vocabulary problem MailSettingsApi.flag solves, and the same answer,
     * because the settings sheet posts to both endpoints from the same code and a
     * checkbox that meant one thing on one and another on the other would be a bug
     * nobody would look for.
     */
    private static boolean flag(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("on") || v.equals("1") || v.equals("yes");
    }

    /** Out of range is clamped by the entity; unparseable falls back rather than 400s. */
    private static int number(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String iso(Instant when) {
        return when == null ? "" : UTC_DATE.format(when.truncatedTo(ChronoUnit.SECONDS));
    }

    // ------------------------------------------------------------------ failures
    //
    // The same shapes MailSettingsApi answers with, because the same script draws both
    // sections of the same sheet and a second error vocabulary would mean a second set
    // of branches in it.

    @ExceptionHandler(MailboxAccess.MailboxLockedException.class)
    public ResponseEntity<?> onLocked(MailboxAccess.MailboxLockedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage(), "locked", true));
    }

    @ExceptionHandler(MailException.class)
    public ResponseEntity<?> onMailFailure(MailException e) {
        String message = e.getMessage() == null ? "That could not be saved." : e.getMessage();
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
                .body(Map.of("error", "Notification rules hit an unexpected fault: "
                        + e.getClass().getSimpleName()));
    }
}
