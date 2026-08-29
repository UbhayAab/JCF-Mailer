package com.jarurat.mailer.push;

import com.jarurat.mailer.push.NotificationRules.Arrival;
import com.jarurat.mailer.push.NotificationRules.Decision;
import com.jarurat.mailer.push.NotificationRules.Lane;
import com.jarurat.mailer.push.NotificationRules.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that decides whether people keep notifications switched on.
 *
 * These are unit tests with no Spring context and no database, deliberately. The lane a
 * message earns is a pure function of the row and the message, and the failure this file
 * is guarding against is not a mapping fault, it is a judgement fault: a rule that says
 * Interrupt for a message nobody wanted to be interrupted by. That failure is silent, it
 * arrives one message at a time, and by the time anybody reports it they have already
 * turned the whole feature off and will not turn it back on.
 *
 * Every assertion checks the Reason as well as the Lane. Checking only the lane would let
 * a test keep passing while the right answer arrived for the wrong reason, which on a rule
 * this branchy is the more likely regression of the two.
 */
class NotificationRulesTest {

    private static final String MAILBOX = "info@jarurat.care";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** 11:04 on a Tuesday, comfortably outside the quiet window. */
    private static final Instant WORKDAY = ist(2026, 9, 8, 11, 4);

    /** 02:40, the middle of the night the quiet-hours argument is about. */
    private static final Instant NIGHT = ist(2026, 9, 8, 2, 40);

    private static Instant ist(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(IST).toInstant();
    }

    private static NotificationRules mailbox() {
        return new NotificationRules(MAILBOX);
    }

    /** A message from a human, in the Inbox, unread. Everything else is set by the caller. */
    private static Arrival from(String sender, List<String> to, List<String> cc) {
        return new Arrival(sender, to, cc, "inbox", false, false, false, false);
    }

    /** The fifty-address Cc line this whole design turns on. */
    private static List<String> crowd(int size, String... always) {
        List<String> out = new ArrayList<>(List.of(always));
        for (int i = 0; i < size; i++) out.add("person" + i + "@example.org");
        return out;
    }

    // ==================================================================
    // The case that decides whether the feature survives
    // ==================================================================


    @Test
    @DisplayName("direct: a Cc to a large list does not interrupt, and is still delivered silently")
    void ccToALargeListIsNotAnInterruption() {
        // The exact shape of the message that kills this feature if it is got wrong: a
        // circular to fifty people with this shared alias on the Cc line. It is not
        // addressed to anybody here, nobody here owes it an answer, and a sound for it
        // is a sound that arrives at three colleagues at once.
        Arrival circular = from("secretary@tmc.gov.in",
                List.of("committee@tmc.gov.in"),
                crowd(50, MAILBOX));

        Decision decision = mailbox().decide(circular, WORKDAY);

        assertThat(decision.lane()).isEqualTo(Lane.DELIVER);
        assertThat(decision.reason()).isEqualTo(Reason.NOT_DIRECT);
        assertThat(decision.interrupts()).isFalse();
        // Delivered, not dropped. Nothing is hidden; it just does not make a noise.
        assertThat(decision.notifies()).isTrue();
        assertThat(decision.quietMuted()).isFalse();
    }

    @Test
    @DisplayName("direct: a Cc to two people is still a Cc, so size alone is not the rule")
    void aSmallCcIsStillNotDirect() {
        Arrival note = from("priya@partner.example",
                List.of("rahul@partner.example"), List.of(MAILBOX));

        Decision decision = mailbox().decide(note, WORKDAY);

        assertThat(decision.lane()).isEqualTo(Lane.DELIVER);
        assertThat(decision.reason()).isEqualTo(Reason.NOT_DIRECT);
    }

    @Test
    @DisplayName("direct: this mailbox on a short To line is somebody choosing you, so it interrupts")
    void toLineOnASmallThreadInterrupts() {
        Arrival referral = from("anand.mehta@tmc.gov.in",
                List.of(MAILBOX, "priya@jarurat.care"), List.of());

        Decision decision = mailbox().decide(referral, WORKDAY);

        assertThat(decision.lane()).isEqualTo(Lane.INTERRUPT);
        assertThat(decision.reason()).isEqualTo(Reason.DIRECT_TO_ME);
    }

    @Test
    @DisplayName("direct: a To line long enough to be a mail merge is not somebody choosing you")
    void aHugeToLineIsNotDirect() {
        // A blast that skipped Bcc. Without this the Direct setting quietly becomes the
        // Everything setting on exactly the mail people resent most.
        Arrival blast = from("updates@vendor.example",
                crowd(NotificationRules.MAX_DIRECT_RECIPIENTS + 4, MAILBOX), List.of());

        Decision decision = mailbox().decide(blast, WORKDAY);

        assertThat(decision.lane()).isEqualTo(Lane.DELIVER);
        assertThat(decision.reason()).isEqualTo(Reason.NOT_DIRECT);
    }

    @Test
    @DisplayName("direct: the boundary is inclusive, so a thread of exactly the cap still counts")
    void theRecipientCapIsInclusive() {
        List<String> to = crowd(NotificationRules.MAX_DIRECT_RECIPIENTS - 1, MAILBOX);
        assertThat(to).hasSize(NotificationRules.MAX_DIRECT_RECIPIENTS);

        Decision decision = mailbox().decide(from("clerk@tmc.gov.in", to, List.of()), WORKDAY);

        assertThat(decision.reason()).isEqualTo(Reason.DIRECT_TO_ME);
    }

    @Test
    @DisplayName("direct: the address is matched case and whitespace insensitively")
    void theAddressComparisonIsNormalised() {
        Arrival shouty = from("clerk@tmc.gov.in", List.of("  INFO@Jarurat.Care "), List.of());

        assertThat(mailbox().decide(shouty, WORKDAY).reason()).isEqualTo(Reason.DIRECT_TO_ME);
    }

    // ==================================================================
    // The levels
    // ==================================================================


    @Test
    @DisplayName("levels: the Inbox notifies out of the box and every other folder does not")
    void theDefaultsAreDirectForTheInboxAndNothingElsewhere() {
        NotificationRules rules = mailbox();

        assertThat(rules.levelFor("inbox")).isEqualTo(NotificationRules.LEVEL_DIRECT);
        assertThat(rules.levelFor("archive")).isEqualTo(NotificationRules.LEVEL_NOTHING);
        assertThat(rules.levelFor("sent")).isEqualTo(NotificationRules.LEVEL_NOTHING);
        assertThat(rules.levelFor("some-folder-somebody-made"))
                .isEqualTo(NotificationRules.LEVEL_NOTHING);
    }

    @Test
    @DisplayName("levels: Everything lets ordinary human mail interrupt, which Direct does not")
    void everythingPromotesTheMailDirectWouldOnlyDeliver() {
        Arrival ccd = from("donor@example.org", List.of("someone@else.example"), List.of(MAILBOX));

        assertThat(mailbox().decide(ccd, WORKDAY).lane()).isEqualTo(Lane.DELIVER);

        NotificationRules loud = mailbox();
        loud.setLevel("inbox", NotificationRules.LEVEL_EVERYTHING);
        Decision decision = loud.decide(ccd, WORKDAY);

        assertThat(decision.lane()).isEqualTo(Lane.INTERRUPT);
        assertThat(decision.reason()).isEqualTo(Reason.HUMAN_MAIL);
    }

    @Test
    @DisplayName("levels: VIPs only shows nothing at all for anybody else, not even silently")
    void vipOnlyIsTheOneLevelThatHidesMail() {
        NotificationRules rules = mailbox();
        rules.setLevel("inbox", NotificationRules.LEVEL_VIP);

        Decision stranger = rules.decide(from("donor@example.org", List.of(MAILBOX), List.of()),
                WORKDAY);
        assertThat(stranger.lane()).isEqualTo(Lane.COUNT);
        assertThat(stranger.reason()).isEqualTo(Reason.NOT_A_VIP);
        assertThat(stranger.notifies()).isFalse();

        rules.setVips(Map.of("anand.mehta@tmc.gov.in", false));
        Decision vip = rules.decide(
                from("anand.mehta@tmc.gov.in", List.of(MAILBOX), List.of()), WORKDAY);
        assertThat(vip.lane()).isEqualTo(Lane.INTERRUPT);
        assertThat(vip.reason()).isEqualTo(Reason.VIP);
    }

    @Test
    @DisplayName("levels: Nothing silences a folder completely")
    void nothingMeansNothing() {
        NotificationRules rules = mailbox();
        rules.setLevel("inbox", NotificationRules.LEVEL_NOTHING);

        Decision decision = rules.decide(from("anand.mehta@tmc.gov.in", List.of(MAILBOX),
                List.of()), WORKDAY);

        assertThat(decision.lane()).isEqualTo(Lane.COUNT);
        assertThat(decision.reason()).isEqualTo(Reason.FOLDER_SILENT);
    }

    @Test
    @DisplayName("levels: a level is per folder, so raising the Archive leaves the Inbox alone")
    void levelsAreIndependentPerFolder() {
        NotificationRules rules = mailbox();
        rules.setLevel("archive", NotificationRules.LEVEL_EVERYTHING);

        assertThat(rules.levelFor("archive")).isEqualTo(NotificationRules.LEVEL_EVERYTHING);
        assertThat(rules.levelFor("inbox")).isEqualTo(NotificationRules.LEVEL_DIRECT);
    }

    @Test
    @DisplayName("levels: an unknown level is ignored rather than stored, so the screen still draws")
    void anUnknownLevelIsIgnored() {
        NotificationRules rules = mailbox();
        rules.setLevel("inbox", "loud-please");

        assertThat(rules.levelFor("inbox")).isEqualTo(NotificationRules.LEVEL_DIRECT);
    }

    // ==================================================================
    // The refusals no setting can overturn
    // ==================================================================


    @Test
    @DisplayName("never: spam never notifies, and a VIP rule cannot rescue it")
    void junkIsNeverNotifiedEvenForAVip() {
        // The whole technique of a phishing message is to name somebody you trust, so
        // a VIP entry is the last thing that should be able to put one on a lock screen.
        NotificationRules rules = mailbox();
        rules.setLevel("inbox", NotificationRules.LEVEL_EVERYTHING);
        rules.setVips(Map.of("@tmc.gov.in", true));

        for (String folder : List.of("junk", "spam", "trash", "drafts")) {
            Arrival spoof = new Arrival("anand.mehta@tmc.gov.in", List.of(MAILBOX), List.of(),
                    folder, false, false, false, false);
            Decision decision = rules.decide(spoof, WORKDAY);

            assertThat(decision.lane()).as(folder).isEqualTo(Lane.COUNT);
            assertThat(decision.reason()).as(folder).isEqualTo(Reason.QUARANTINED_FOLDER);
        }
    }

    @Test
    @DisplayName("never: a quarantined folder cannot even be given a level")
    void junkCannotBeConfiguredToNotify() {
        NotificationRules rules = mailbox();
        rules.setLevel("junk", NotificationRules.LEVEL_EVERYTHING);

        assertThat(rules.levelFor("junk")).isEqualTo(NotificationRules.LEVEL_NOTHING);
        assertThat(rules.getFolderLevels()).doesNotContainKey("junk");
    }

    @Test
    @DisplayName("never: a message already read on another device is not an event")
    void anAlreadySeenMessageIsNotAnnounced() {
        Arrival read = new Arrival("anand.mehta@tmc.gov.in", List.of(MAILBOX), List.of(),
                "inbox", true, false, false, false);

        Decision decision = mailbox().decide(read, WORKDAY);

        assertThat(decision.lane()).isEqualTo(Lane.COUNT);
        assertThat(decision.reason()).isEqualTo(Reason.ALREADY_SEEN);
    }

    @Test
    @DisplayName("never: the mailbox's own message never notifies the mailbox")
    void ownMailIsNeverAnnounced() {
        Arrival echo = from(MAILBOX, List.of(MAILBOX), List.of());

        assertThat(mailbox().decide(echo, WORKDAY).reason()).isEqualTo(Reason.OWN_MESSAGE);
    }

    // ==================================================================
    // Robots
    // ==================================================================


    @Test
    @DisplayName("robots: a noreply address is counted, not announced, even addressed directly")
    void aNoreplySenderIsCounted() {
        Arrival payslip = from("noreply@payroll.jarurat.care", List.of(MAILBOX), List.of());

        Decision decision = mailbox().decide(payslip, WORKDAY);

        assertThat(decision.lane()).isEqualTo(Lane.COUNT);
        assertThat(decision.reason()).isEqualTo(Reason.AUTOMATED);
    }

    @Test
    @DisplayName("robots: List-Unsubscribe and Auto-Submitted are believed when the caller reads them")
    void headerFlagsAreEnoughOnTheirOwn() {
        Arrival newsletter = new Arrival("editor@charitynews.example", List.of(MAILBOX),
                List.of(), "inbox", false, false, true, false);
        Arrival autoReply = new Arrival("desk@vendor.example", List.of(MAILBOX),
                List.of(), "inbox", false, true, false, false);

        assertThat(mailbox().decide(newsletter, WORKDAY).reason()).isEqualTo(Reason.AUTOMATED);
        assertThat(mailbox().decide(autoReply, WORKDAY).reason()).isEqualTo(Reason.AUTOMATED);
    }

    @Test
    @DisplayName("robots: a VIP beats the robot rule, because a person said so and we only guessed")
    void aVipOverridesTheRobotRule() {
        NotificationRules rules = mailbox();
        rules.setVips(Map.of("noreply@payroll.jarurat.care", false));

        Decision decision = rules.decide(
                from("noreply@payroll.jarurat.care", List.of(MAILBOX), List.of()), WORKDAY);

        assertThat(decision.lane()).isEqualTo(Lane.INTERRUPT);
        assertThat(decision.reason()).isEqualTo(Reason.VIP);
    }

    @Test
    @DisplayName("robots: raising the level to Everything does not promote robots")
    void everythingStillMeansEveryHuman() {
        NotificationRules rules = mailbox();
        rules.setLevel("inbox", NotificationRules.LEVEL_EVERYTHING);

        Decision decision = rules.decide(
                from("mailer-daemon@vendor.example", List.of(MAILBOX), List.of()), WORKDAY);

        assertThat(decision.lane()).isEqualTo(Lane.COUNT);
        assertThat(decision.reason()).isEqualTo(Reason.AUTOMATED);
    }

    // ==================================================================
    // VIPs and mutes
    // ==================================================================


    @Test
    @DisplayName("lists: a whole domain can be a VIP, which is how a hospital gets on the list")
    void aDomainEntryMatchesEverybodyThere() {
        NotificationRules rules = mailbox();
        rules.setVips(Map.of("@tmc.gov.in", false));

        assertThat(rules.isVip("anybody@tmc.gov.in")).isTrue();
        assertThat(rules.isVip("anybody@tmc.gov.in.example")).isFalse();
        assertThat(rules.isVip("anybody@elsewhere.example")).isFalse();
    }

    @Test
    @DisplayName("lists: a bare domain typed without the at sign is read as a domain")
    void aBareDomainIsAccepted() {
        assertThat(NotificationRules.normaliseVip("tmc.gov.in")).isEqualTo("@tmc.gov.in");
        assertThat(NotificationRules.normaliseVip("  Anand@TMC.gov.in "))
                .isEqualTo("anand@tmc.gov.in");
        assertThat(NotificationRules.normaliseVip("not an address")).isNull();
        assertThat(NotificationRules.normaliseVip("nodots")).isNull();
        assertThat(NotificationRules.normaliseVip("two@at@signs.example")).isNull();
    }

    @Test
    @DisplayName("lists: the list stops at the cap rather than growing without limit")
    void theVipListIsCapped() {
        NotificationRules rules = mailbox();
        Map<String, Boolean> tooMany = new java.util.LinkedHashMap<>();
        for (int i = 0; i < NotificationRules.MAX_VIPS + 25; i++) {
            tooMany.put("person" + i + "@example.org", false);
        }
        rules.setVips(tooMany);

        assertThat(rules.vipList()).hasSize(NotificationRules.MAX_VIPS);
    }

    @Test
    @DisplayName("lists: a muted sender is counted, and the mute runs out on its own")
    void muteExpires() {
        NotificationRules rules = mailbox();
        rules.mute("chatty@example.org", WORKDAY);

        Decision muted = rules.decide(from("chatty@example.org", List.of(MAILBOX), List.of()),
                WORKDAY);
        assertThat(muted.lane()).isEqualTo(Lane.COUNT);
        assertThat(muted.reason()).isEqualTo(Reason.MUTED_SENDER);

        Instant later = WORKDAY.plusSeconds((NotificationRules.MUTE_DAYS + 1) * 86400L);
        Decision after = rules.decide(from("chatty@example.org", List.of(MAILBOX), List.of()),
                later);
        assertThat(after.reason()).isEqualTo(Reason.DIRECT_TO_ME);
    }

    @Test
    @DisplayName("lists: a VIP cannot be silently muted, because the person said the opposite twice")
    void vipBeatsMute() {
        NotificationRules rules = mailbox();
        rules.mute("anand.mehta@tmc.gov.in", WORKDAY);
        rules.setVips(Map.of("anand.mehta@tmc.gov.in", false));

        assertThat(rules.decide(from("anand.mehta@tmc.gov.in", List.of(MAILBOX), List.of()),
                WORKDAY).reason()).isEqualTo(Reason.VIP);
    }

    // ==================================================================
    // Quiet hours
    // ==================================================================


    @Test
    @DisplayName("quiet: the defaults are the journey engine's: 21:00 to 08:00, Asia/Kolkata")
    void theDefaultsMatchTheJourneyEngine() {
        NotificationRules rules = mailbox();

        assertThat(rules.isQuietEnabled()).isTrue();
        assertThat(rules.getQuietStartHour()).isEqualTo(21);
        assertThat(rules.getQuietEndHour()).isEqualTo(8);
        assertThat(rules.getZoneId()).isEqualTo("Asia/Kolkata");
    }

    @Test
    @DisplayName("quiet: the window reads as overnight, the same way applyQuietHours does")
    void theOvernightWindowIsReadTheSameWay() {
        NotificationRules rules = mailbox();

        assertThat(rules.quiet(ist(2026, 9, 8, 21, 0))).isTrue();   // start is inclusive
        assertThat(rules.quiet(ist(2026, 9, 8, 23, 59))).isTrue();
        assertThat(rules.quiet(ist(2026, 9, 8, 0, 1))).isTrue();
        assertThat(rules.quiet(ist(2026, 9, 8, 7, 59))).isTrue();
        assertThat(rules.quiet(ist(2026, 9, 8, 8, 0))).isFalse();   // end is exclusive
        assertThat(rules.quiet(ist(2026, 9, 8, 20, 59))).isFalse();
    }

    @Test
    @DisplayName("quiet: start equal to end means no quiet window at all, as in the journey engine")
    void anEmptyWindowIsNoWindow() {
        NotificationRules rules = mailbox();
        rules.setQuietHours(9, 9);

        assertThat(rules.quiet(NIGHT)).isFalse();
    }

    @Test
    @DisplayName("quiet: a night arrival is shown at once and silently, never held until morning")
    void quietHoursTakeTheSoundOffAndNothingElse() {
        // The choice, and the reason for it: the mail has already arrived. Holding the
        // notification would move only our report of it, and somebody who checks their
        // phone at 03:00 and sees nothing concludes nothing came.
        Arrival referral = from("anand.mehta@tmc.gov.in", List.of(MAILBOX), List.of());

        Decision decision = mailbox().decide(referral, NIGHT);

        assertThat(decision.lane()).isEqualTo(Lane.DELIVER);
        assertThat(decision.quietMuted()).isTrue();
        // Still shown, and still carrying the reason it was judged an interruption, so
        // the shade at 07:00 says why it is there.
        assertThat(decision.notifies()).isTrue();
        assertThat(decision.reason()).isEqualTo(Reason.DIRECT_TO_ME);
    }

    @Test
    @DisplayName("quiet: a VIP with break-through is the only thing that makes a sound at 02:40")
    void onlyAnOptedInVipBreaksThrough() {
        NotificationRules quietVip = mailbox();
        quietVip.setVips(Map.of("@tmc.gov.in", false));
        Decision hushed = quietVip.decide(
                from("anand.mehta@tmc.gov.in", List.of(MAILBOX), List.of()), NIGHT);
        assertThat(hushed.lane()).isEqualTo(Lane.DELIVER);
        assertThat(hushed.quietMuted()).isTrue();

        NotificationRules loudVip = mailbox();
        loudVip.setVips(Map.of("@tmc.gov.in", true));
        Decision through = loudVip.decide(
                from("anand.mehta@tmc.gov.in", List.of(MAILBOX), List.of()), NIGHT);
        assertThat(through.lane()).isEqualTo(Lane.INTERRUPT);
        assertThat(through.quietMuted()).isFalse();
    }

    @Test
    @DisplayName("quiet: break-through is off unless it was asked for, including for a new VIP")
    void breakThroughIsOptIn() {
        NotificationRules rules = mailbox();
        rules.setVips(Map.of("anand.mehta@tmc.gov.in", true, "@partner.example", false));

        assertThat(rules.breaksThroughQuiet("anand.mehta@tmc.gov.in")).isTrue();
        assertThat(rules.breaksThroughQuiet("someone@partner.example")).isFalse();
        assertThat(rules.breaksThroughQuiet("stranger@example.org")).isFalse();
    }

    @Test
    @DisplayName("quiet: switching quiet hours off restores the sound and changes nothing else")
    void quietHoursCanBeSwitchedOff() {
        NotificationRules rules = mailbox();
        rules.setQuietEnabled(false);

        Decision decision = rules.decide(
                from("anand.mehta@tmc.gov.in", List.of(MAILBOX), List.of()), NIGHT);

        assertThat(decision.lane()).isEqualTo(Lane.INTERRUPT);
        assertThat(decision.quietMuted()).isFalse();
    }

    @Test
    @DisplayName("quiet: the window is read in its own zone and not the server's")
    void theZoneIsTheMailboxesAndNotTheBoxes() {
        NotificationRules london = mailbox();
        london.setZoneId("Europe/London");

        // 02:40 IST is 22:10 the previous evening in London, which is inside a 21 to 8
        // window there as well; 12:00 IST is 07:30 London, which is still inside it.
        assertThat(london.quiet(ist(2026, 9, 8, 12, 0))).isTrue();
        assertThat(london.quiet(ist(2026, 9, 8, 16, 0))).isFalse();   // 11:30 London
    }

    @Test
    @DisplayName("quiet: an unparseable zone is refused rather than stored and failed on later")
    void aBadZoneIsIgnored() {
        NotificationRules rules = mailbox();
        rules.setZoneId("Mars/Olympus_Mons");

        assertThat(rules.getZoneId()).isEqualTo("Asia/Kolkata");
    }

    // ==================================================================
    // Threads and send failures
    // ==================================================================


    @Test
    @DisplayName("zero config: a reply in a thread this mailbox sent into interrupts even from a Cc")
    void aWatchedThreadInterrupts() {
        Arrival reply = new Arrival("clerk@tmc.gov.in", List.of("someone@else.example"),
                crowd(30, MAILBOX), "inbox", false, false, false, true);

        Decision decision = mailbox().decide(reply, WORKDAY);

        assertThat(decision.lane()).isEqualTo(Lane.INTERRUPT);
        assertThat(decision.reason()).isEqualTo(Reason.WATCHED_THREAD);
    }

    @Test
    @DisplayName("zero config: watching a thread does not reach into Junk or past a robot")
    void aWatchedThreadIsStillSubjectToTheRefusals() {
        Arrival spam = new Arrival("clerk@tmc.gov.in", List.of(MAILBOX), List.of(),
                "junk", false, false, false, true);
        Arrival robot = new Arrival("noreply@tmc.gov.in", List.of(MAILBOX), List.of(),
                "inbox", false, false, false, true);

        assertThat(mailbox().decide(spam, WORKDAY).reason()).isEqualTo(Reason.QUARANTINED_FOLDER);
        assertThat(mailbox().decide(robot, WORKDAY).reason()).isEqualTo(Reason.AUTOMATED);
    }

    @Test
    @DisplayName("zero config: a send that failed interrupts whatever the folder levels say")
    void aSendFailureIgnoresTheLevels() {
        NotificationRules silent = mailbox();
        silent.setLevel("inbox", NotificationRules.LEVEL_NOTHING);

        Decision decision = silent.decideSendFailure(WORKDAY);

        assertThat(decision.lane()).isEqualTo(Lane.INTERRUPT);
        assertThat(decision.reason()).isEqualTo(Reason.SEND_FAILED);
    }

    @Test
    @DisplayName("zero config: a send failure still obeys quiet hours, because 02:00 is no time to retry")
    void aSendFailureIsStillSilencedAtNight() {
        Decision decision = mailbox().decideSendFailure(NIGHT);

        assertThat(decision.lane()).isEqualTo(Lane.DELIVER);
        assertThat(decision.quietMuted()).isTrue();
        assertThat(decision.reason()).isEqualTo(Reason.SEND_FAILED);
    }
}
