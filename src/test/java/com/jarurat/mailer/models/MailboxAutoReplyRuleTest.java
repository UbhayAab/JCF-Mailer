package com.jarurat.mailer.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that decides whether an out of office reply is owed.
 *
 * This is the one piece of the settings feature whose failure mode is other people
 * inbox rather than ours. An auto-responder that answers every message in a mailing
 * list thread sends forty identical replies to a few hundred strangers, and the way
 * that ends is the address being removed from the list and somebody at the foundation
 * apologising. So the tests here are mostly about refusals, and the once-per-sender
 * period is the one with the most of them: the boundary at exactly the period, the
 * boundary one second either side, and the fact that a refusal must not quietly move
 * the clock forward and extend itself.
 */
class MailboxAutoReplyRuleTest {

    private static final String MAILBOX = "support@jarurat.care";
    private static final Instant MONDAY = Instant.parse("2026-09-07T09:00:00Z");

    private MailboxSettings answering() {
        MailboxSettings row = new MailboxSettings(MAILBOX);
        row.setVacationEnabled(true);
        row.setVacationHtml("<p>Away at a camp until the 20th.</p>");
        row.setVacationPeriodDays(7);
        return row;
    }

    // ------------------------------------------------------------------ the period

    @Test
    @DisplayName("the first message from a sender is answered and the second is not")
    void oneReplyPerSender() {
        MailboxSettings row = answering();

        assertThat(row.claimAutoReply("dr.rao@hospital.example", false, false, MONDAY))
                .isEqualTo(MailboxSettings.Reply.SEND);
        assertThat(row.claimAutoReply("dr.rao@hospital.example", false, false,
                MONDAY.plus(Duration.ofMinutes(3))))
                .isEqualTo(MailboxSettings.Reply.ALREADY_REPLIED);
    }

    @Test
    @DisplayName("forty messages in one thread produce exactly one reply")
    void aThreadIsNotAConversation() {
        MailboxSettings row = answering();
        int sent = 0;
        for (int i = 0; i < 40; i++) {
            Instant when = MONDAY.plus(Duration.ofMinutes(i * 2L));
            if (row.claimAutoReply("dr.rao@hospital.example", false, false, when)
                    == MailboxSettings.Reply.SEND) {
                sent++;
            }
        }
        assertThat(sent).isEqualTo(1);
    }

    @Test
    @DisplayName("a different sender inside the same period still gets their reply")
    void thePeriodIsPerSenderAndNotPerMailbox() {
        MailboxSettings row = answering();
        row.claimAutoReply("dr.rao@hospital.example", false, false, MONDAY);

        assertThat(row.claimAutoReply("donor@example.org", false, false, MONDAY.plusSeconds(30)))
                .isEqualTo(MailboxSettings.Reply.SEND);
    }

    @Test
    @DisplayName("the same sender is answered again once the period has passed")
    void thePeriodExpires() {
        MailboxSettings row = answering();
        row.claimAutoReply("dr.rao@hospital.example", false, false, MONDAY);

        Instant justInside = MONDAY.plus(Duration.ofDays(7)).minusSeconds(1);
        Instant justOutside = MONDAY.plus(Duration.ofDays(7)).plusSeconds(1);

        assertThat(row.autoReplyDecision("dr.rao@hospital.example", false, false, justInside))
                .isEqualTo(MailboxSettings.Reply.ALREADY_REPLIED);
        assertThat(row.claimAutoReply("dr.rao@hospital.example", false, false, justOutside))
                .isEqualTo(MailboxSettings.Reply.SEND);
    }

    @Test
    @DisplayName("a refusal does not restart the period, so a persistent sender still gets one a week")
    void aRefusalDoesNotExtendItself() {
        MailboxSettings row = answering();
        row.claimAutoReply("keen@example.org", false, false, MONDAY);

        // Writing every single day for a week must not push the next reply out to day 14.
        for (int day = 1; day <= 6; day++) {
            row.claimAutoReply("keen@example.org", false, false, MONDAY.plus(Duration.ofDays(day)));
        }

        assertThat(row.claimAutoReply("keen@example.org", false, false,
                MONDAY.plus(Duration.ofDays(7)).plusSeconds(1)))
                .isEqualTo(MailboxSettings.Reply.SEND);
    }

    @Test
    @DisplayName("a shorter period is honoured rather than rounded to the default week")
    void thePeriodIsTheStoredOne() {
        MailboxSettings row = answering();
        row.setVacationPeriodDays(1);
        row.claimAutoReply("dr.rao@hospital.example", false, false, MONDAY);

        assertThat(row.claimAutoReply("dr.rao@hospital.example", false, false,
                MONDAY.plus(Duration.ofDays(1)).plusSeconds(1)))
                .isEqualTo(MailboxSettings.Reply.SEND);
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    @DisplayName("a mailing list is never answered, however long ago the last reply was")
    void aListIsNeverAnswered() {
        MailboxSettings row = answering();

        assertThat(row.claimAutoReply("oncology-india@lists.example", false, true, MONDAY))
                .isEqualTo(MailboxSettings.Reply.LIST);
        assertThat(row.autoRepliedCount()).isZero();
    }

    @Test
    @DisplayName("a message that declares itself automatic is never answered")
    void autoSubmittedIsNeverAnswered() {
        assertThat(answering().claimAutoReply("real.person@example.org", true, false, MONDAY))
                .isEqualTo(MailboxSettings.Reply.AUTOMATED);
    }

    @ParameterizedTest
    @DisplayName("addresses that no human reads are refused on the local part alone")
    @ValueSource(strings = {
            "noreply@bank.example", "no-reply@portal.example", "MAILER-DAEMON@relay.example",
            "postmaster@relay.example", "bounces+abc=jarurat.care@list.example",
            "owner-oncology@lists.example", "donotreply@gov.example"})
    void robotsAreRefused(String sender) {
        assertThat(answering().claimAutoReply(sender, false, false, MONDAY))
                .isEqualTo(MailboxSettings.Reply.AUTOMATED);
    }

    @Test
    @DisplayName("an address that merely contains the word is still a person")
    void aRealPersonIsNotARobot() {
        assertThat(answering().claimAutoReply("hari.noreplyan@example.org", false, false, MONDAY))
                .isEqualTo(MailboxSettings.Reply.SEND);
    }

    @Test
    @DisplayName("the mailbox never answers itself, whatever the case of the address")
    void noSelfLoop() {
        assertThat(answering().claimAutoReply("SUPPORT@Jarurat.Care", false, false, MONDAY))
                .isEqualTo(MailboxSettings.Reply.SELF);
    }

    @Test
    @DisplayName("no sender means nothing to answer")
    void anEmptyEnvelopeSenderIsRefused() {
        assertThat(answering().claimAutoReply("  ", false, false, MONDAY))
                .isEqualTo(MailboxSettings.Reply.NO_SENDER);
    }

    @Test
    @DisplayName("switched off answers nobody")
    void offMeansOff() {
        MailboxSettings row = answering();
        row.setVacationEnabled(false);

        assertThat(row.claimAutoReply("donor@example.org", false, false, MONDAY))
                .isEqualTo(MailboxSettings.Reply.OFF);
    }

    @Test
    @DisplayName("outside the dates answers nobody, at either end")
    void theWindowIsClosedAtBothEnds() {
        MailboxSettings row = answering();
        row.setVacationFrom(Instant.parse("2026-09-10T00:00:00Z"));
        row.setVacationTo(Instant.parse("2026-09-20T00:00:00Z"));

        assertThat(row.claimAutoReply("donor@example.org", false, false, MONDAY))
                .isEqualTo(MailboxSettings.Reply.OUTSIDE_WINDOW);
        assertThat(row.claimAutoReply("donor@example.org", false, false,
                Instant.parse("2026-09-21T00:00:00Z")))
                .isEqualTo(MailboxSettings.Reply.OUTSIDE_WINDOW);
        assertThat(row.claimAutoReply("donor@example.org", false, false,
                Instant.parse("2026-09-15T00:00:00Z")))
                .isEqualTo(MailboxSettings.Reply.SEND);
    }

    @Test
    @DisplayName("an open ended absence has no end date and still answers")
    void anOpenEndedAbsence() {
        MailboxSettings row = answering();
        row.setVacationFrom(Instant.parse("2026-09-01T00:00:00Z"));
        row.setVacationTo(null);

        assertThat(row.vacationActive(MONDAY.plus(Duration.ofDays(400)))).isTrue();
    }

    // ------------------------------------------------------------------ the ledger

    @Test
    @DisplayName("deciding without claiming does not record anything")
    void aDecisionIsNotAClaim() {
        MailboxSettings row = answering();

        assertThat(row.autoReplyDecision("donor@example.org", false, false, MONDAY))
                .isEqualTo(MailboxSettings.Reply.SEND);
        assertThat(row.autoReplyDecision("donor@example.org", false, false, MONDAY))
                .isEqualTo(MailboxSettings.Reply.SEND);
        assertThat(row.autoRepliedCount()).isZero();
    }

    @Test
    @DisplayName("expired entries are dropped rather than kept for ever")
    void theLedgerIsPruned() {
        MailboxSettings row = answering();
        row.claimAutoReply("one@example.org", false, false, MONDAY);
        row.claimAutoReply("two@example.org", false, false, MONDAY);
        assertThat(row.autoRepliedCount()).isEqualTo(2);

        row.forgetExpired(MONDAY.plus(Duration.ofDays(8)));
        assertThat(row.autoRepliedCount()).isZero();
    }

    @Test
    @DisplayName("the ledger will not grow past its ceiling")
    void theLedgerIsBounded() {
        MailboxSettings row = answering();
        for (int i = 0; i < MailboxSettings.MAX_TRACKED_SENDERS + 50; i++) {
            row.claimAutoReply("sender" + i + "@example.org", false, false,
                    MONDAY.plusSeconds(i));
        }
        assertThat(row.autoRepliedCount()).isLessThanOrEqualTo(MailboxSettings.MAX_TRACKED_SENDERS);
    }

    @Test
    @DisplayName("the sender is remembered case-insensitively, because addresses are")
    void theLedgerIsCaseInsensitive() {
        MailboxSettings row = answering();
        row.claimAutoReply("Dr.Rao@Hospital.Example", false, false, MONDAY);

        assertThat(row.claimAutoReply("dr.rao@hospital.example", false, false, MONDAY.plusSeconds(60)))
                .isEqualTo(MailboxSettings.Reply.ALREADY_REPLIED);
        assertThat(row.lastAutoReply("DR.RAO@HOSPITAL.EXAMPLE")).isEqualTo(MONDAY);
    }
}
