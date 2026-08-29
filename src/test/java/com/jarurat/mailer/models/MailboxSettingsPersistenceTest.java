package com.jarurat.mailer.models;

import com.jarurat.mailer.repositories.MailboxSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the row and its reply ledger survive a round trip, and that two mailboxes stay
 * two rows.
 *
 * The unit tests above prove the rule and the API test proves the endpoint reaches the
 * right key, but neither of them touches a database, and the thing most likely to go
 * wrong here is the mapping rather than the logic: the auto reply ledger is an element
 * collection in a second table joined on the mailbox address, and a collection that
 * comes back empty would silently turn the once-per-sender rule into once per process
 * restart. That failure is invisible in a mock, and it answers a donor twice.
 */
@DataJpaTest
class MailboxSettingsPersistenceTest {

    private static final Instant MONDAY = Instant.parse("2026-09-07T09:00:00Z");

    @Autowired
    private MailboxSettingsRepository repository;

    @Test
    @DisplayName("the reply ledger comes back off the database, so the rule survives a restart")
    void theLedgerRoundTrips() {
        MailboxSettings row = new MailboxSettings("support@jarurat.care");
        row.setVacationEnabled(true);
        row.setVacationHtml("<p>Away.</p>");
        row.setVacationPeriodDays(7);
        row.claimAutoReply("dr.rao@hospital.example", false, false, MONDAY);
        repository.saveAndFlush(row);

        MailboxSettings back = repository.findById("support@jarurat.care").orElseThrow();
        assertThat(back.autoRepliedCount()).isEqualTo(1);
        assertThat(back.autoReplyDecision("dr.rao@hospital.example", false, false,
                MONDAY.plus(Duration.ofHours(2))))
                .isEqualTo(MailboxSettings.Reply.ALREADY_REPLIED);
        assertThat(back.autoReplyDecision("donor@example.org", false, false, MONDAY))
                .isEqualTo(MailboxSettings.Reply.SEND);
    }

    @Test
    @DisplayName("two mailboxes are two rows and two ledgers")
    void mailboxesAreIsolatedInTheDatabase() {
        MailboxSettings support = new MailboxSettings("support@jarurat.care");
        support.setVacationEnabled(true);
        support.setVacationHtml("<p>The support desk is away.</p>");
        support.setSignatureHtml("<p>Support desk</p>");
        support.claimAutoReply("donor@example.org", false, false, MONDAY);

        MailboxSettings priya = new MailboxSettings("priya@jarurat.care");
        priya.setSignatureHtml("<p>Priya, programmes</p>");
        priya.setMessagesPerPage(25);

        repository.saveAndFlush(support);
        repository.saveAndFlush(priya);

        MailboxSettings storedSupport = repository.findById("support@jarurat.care").orElseThrow();
        MailboxSettings storedPriya = repository.findById("priya@jarurat.care").orElseThrow();

        assertThat(storedSupport.autoRepliedCount()).isEqualTo(1);
        // The same donor has had nothing from this mailbox, so it must not be suppressed.
        assertThat(storedPriya.autoRepliedCount()).isZero();
        assertThat(storedPriya.getSignatureHtml()).doesNotContain("Support desk");
        assertThat(storedPriya.getMessagesPerPage()).isEqualTo(25);
        assertThat(storedSupport.getMessagesPerPage()).isEqualTo(50);

        assertThat(repository.findByVacationEnabledTrue())
                .extracting(MailboxSettings::getMailbox)
                .containsExactly("support@jarurat.care");
    }

    @Test
    @DisplayName("the address is the key, so a mailbox has one row however often it is saved")
    void theAddressIsTheKey() {
        repository.saveAndFlush(new MailboxSettings("SUPPORT@Jarurat.Care"));

        MailboxSettings again = repository.findById("support@jarurat.care").orElseThrow();
        again.setSignatureHtml("<p>Second save</p>");
        repository.saveAndFlush(again);

        assertThat(repository.count()).isEqualTo(1);
        Optional<MailboxSettings> mixedCase = repository.findById("SUPPORT@Jarurat.Care");
        // Normalised on construction, so the mixed case address is simply not a key.
        assertThat(mixedCase).isEmpty();
    }
}
