package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.models.MailboxSettings;
import com.jarurat.mailer.repositories.MailboxSettingsRepository;
import com.jarurat.mailer.services.AuditService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the settings endpoint stores, what it refuses, and above all which mailbox it
 * touches.
 *
 * The isolation tests are the ones that earn their place. Every other endpoint in
 * webmail resolves the mailbox through MailboxAccess and takes no mailbox parameter,
 * and a settings screen is the obvious place for somebody to break that rule later by
 * adding one, because settings feel administrative in a way that reading mail does
 * not. These assert on the repository key rather than only on the response, so a
 * future parameter that reached the row would fail here rather than in production.
 */
class MailSettingsApiTest {

    private static final String SUPPORT = "support@jarurat.care";
    private static final String PRIYA = "priya@jarurat.care";

    private final ObjectMapper json = new ObjectMapper();
    private final MailboxSettingsRepository repository = mock(MailboxSettingsRepository.class);
    private final MailboxAccess mailbox = mock(MailboxAccess.class);
    private final JmapClient jmap = mock(JmapClient.class);
    private final AuditService audit = mock(AuditService.class);

    private final MailSettingsApi api = new MailSettingsApi(repository, mailbox, jmap, audit);
    private final HttpSession session = new MockHttpSession();

    /** What the repository would have in Postgres, keyed the way the entity is keyed. */
    private final Map<String, MailboxSettings> stored = new HashMap<>();

    /** Every methodCalls array handed to the mail server, so the JSON can be read back. */
    private final List<ArrayNode> jmapRequests = new ArrayList<>();

    /** The capability list on the last request, which is what a missing capability shows up in. */
    private final List<List<String>> jmapUsing = new ArrayList<>();

    @BeforeEach
    void wire() {
        when(repository.findById(anyString()))
                .thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0, String.class))));
        when(repository.save(any(MailboxSettings.class))).thenAnswer(i -> {
            MailboxSettings row = i.getArgument(0);
            stored.put(row.getMailbox(), row);
            return row;
        });

        when(jmap.newObject()).thenAnswer(i -> json.createObjectNode());
        when(jmap.newArray()).thenAnswer(i -> json.createArrayNode());
        when(jmap.accountArgs(anyString()))
                .thenAnswer(i -> json.createObjectNode().put("accountId", "acc-1"));
        when(jmap.invocation(anyString(), any(), anyString())).thenAnswer(i -> {
            ArrayNode call = json.createArrayNode();
            call.add(i.getArgument(0, String.class));
            call.add((JsonNode) i.getArgument(1));
            call.add(i.getArgument(2, String.class));
            return call;
        });
        acceptVacation();
        when(jmap.response(any(), anyString(), anyString())).thenAnswer(i -> {
            JsonNode responses = i.getArgument(0);
            return responses.path(0).path(1);
        });

        openMailbox(SUPPORT);
    }

    /** The mail server takes the vacation response and says nothing was refused. */
    private void acceptVacation() {
        when(jmap.call(anyString(), anyList(), any())).thenAnswer(i -> {
            jmapUsing.add(i.getArgument(1));
            jmapRequests.add(i.getArgument(2));
            ObjectNode result = json.createObjectNode();
            result.putObject("updated").putNull("singleton");
            ArrayNode responses = json.createArrayNode();
            ArrayNode entry = json.createArrayNode();
            entry.add("VacationResponse/set");
            entry.add(result);
            entry.add("v0");
            responses.add(entry);
            return responses;
        });
    }

    private void openMailbox(String address) {
        when(mailbox.require(any(), any())).thenReturn(address);
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("the row that is written is the mailbox pinned in the session")
    void theSessionDecidesTheRow() {
        openMailbox(SUPPORT);
        save(Map.of("signatureHtml", "<p>Support desk</p>"));

        assertThat(stored).containsOnlyKeys(SUPPORT);
        assertThat(stored.get(SUPPORT).getSignatureHtml()).contains("Support desk");
    }

    @Test
    @DisplayName("two mailboxes keep two separate settings and neither can see the other")
    void settingsDoNotLeakBetweenMailboxes() {
        openMailbox(SUPPORT);
        save(Map.of("signatureHtml", "<p>Support desk</p>", "messagesPerPage", "25",
                "readingPane", "below"));

        openMailbox(PRIYA);
        save(Map.of("signatureHtml", "<p>Priya, programmes</p>", "messagesPerPage", "100"));

        assertThat(stored.keySet()).containsExactlyInAnyOrder(SUPPORT, PRIYA);
        assertThat(stored.get(SUPPORT).getSignatureHtml()).contains("Support desk");
        assertThat(stored.get(SUPPORT).getMessagesPerPage()).isEqualTo(25);
        assertThat(stored.get(SUPPORT).getReadingPane()).isEqualTo(MailboxSettings.PANE_BELOW);

        assertThat(stored.get(PRIYA).getSignatureHtml()).contains("Priya, programmes");
        assertThat(stored.get(PRIYA).getMessagesPerPage()).isEqualTo(100);
        // Never set on this mailbox, so it must still be the default and not the neighbour.
        assertThat(stored.get(PRIYA).getReadingPane()).isEqualTo(MailboxSettings.PANE_SIDE);

        Map<String, Object> priyaSees = api.read(null, session);
        assertThat(priyaSees.get("mailbox")).isEqualTo(PRIYA);
        assertThat(String.valueOf(priyaSees.get("signatureHtml"))).doesNotContain("Support desk");
    }

    @Test
    @DisplayName("one mailbox out of office ledger is not the other one")
    void theAutoReplyLedgerIsPerMailbox() {
        MailboxSettings supportRow = new MailboxSettings(SUPPORT);
        supportRow.setVacationEnabled(true);
        supportRow.setVacationHtml("<p>Away</p>");
        MailboxSettings priyaRow = new MailboxSettings(PRIYA);
        priyaRow.setVacationEnabled(true);
        priyaRow.setVacationHtml("<p>Away</p>");
        stored.put(SUPPORT, supportRow);
        stored.put(PRIYA, priyaRow);

        java.time.Instant now = java.time.Instant.parse("2026-09-07T09:00:00Z");
        assertThat(supportRow.claimAutoReply("donor@example.org", false, false, now))
                .isEqualTo(MailboxSettings.Reply.SEND);
        // The same donor writing to a different mailbox is owed a reply from that one.
        assertThat(priyaRow.claimAutoReply("donor@example.org", false, false, now))
                .isEqualTo(MailboxSettings.Reply.SEND);
        assertThat(supportRow.claimAutoReply("donor@example.org", false, false, now))
                .isEqualTo(MailboxSettings.Reply.ALREADY_REPLIED);
    }

    @Test
    @DisplayName("a locked mailbox is refused before anything is read or written")
    void aLockedMailboxTouchesNothing() {
        when(mailbox.require(any(), any()))
                .thenThrow(new MailboxAccess.MailboxLockedException("Open your mailbox."));

        assertThatThrownBy(() -> api.read(null, session))
                .isInstanceOf(MailboxAccess.MailboxLockedException.class);
        assertThatThrownBy(() -> save(Map.of("signatureHtml", "<p>x</p>")))
                .isInstanceOf(MailboxAccess.MailboxLockedException.class);
        verify(repository, never()).save(any());
    }

    // ------------------------------------------------------------------ defaults

    @Test
    @DisplayName("a mailbox with no row reads as the defaults and nothing is written to get them")
    void defaultsWithoutARow() {
        Map<String, Object> out = api.read(null, session);

        assertThat(out.get("mailbox")).isEqualTo(SUPPORT);
        assertThat(out.get("loadRemoteImages")).isEqualTo(false);
        assertThat(out.get("requestReadReceipt")).isEqualTo(false);
        assertThat(out.get("preferHtml")).isEqualTo(true);
        assertThat(out.get("messagesPerPage")).isEqualTo(50);
        assertThat(out.get("readingPane")).isEqualTo(MailboxSettings.PANE_SIDE);
        assertThat(out.get("defaultReply")).isEqualTo(MailboxSettings.REPLY_SENDER);
        assertThat(out.get("vacationEnabled")).isEqualTo(false);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("an absent parameter leaves the stored value alone")
    void aPartialSaveIsPartial() {
        save(Map.of("signatureHtml", "<p>Priya</p>", "messagesPerPage", "25"));
        save(Map.of("readingPane", "below"));

        MailboxSettings row = stored.get(SUPPORT);
        assertThat(row.getSignatureHtml()).contains("Priya");
        assertThat(row.getMessagesPerPage()).isEqualTo(25);
        assertThat(row.getReadingPane()).isEqualTo(MailboxSettings.PANE_BELOW);
    }

    @Test
    @DisplayName("out of range numbers are clamped rather than losing the whole form")
    void numbersAreClamped() {
        save(Map.of("messagesPerPage", "9999", "undoSendSeconds", "600",
                "vacationPeriodDays", "0"));

        MailboxSettings row = stored.get(SUPPORT);
        assertThat(row.getMessagesPerPage()).isEqualTo(MailboxSettings.MAX_PER_PAGE);
        assertThat(row.getUndoSendSeconds()).isEqualTo(MailboxSettings.MAX_UNDO_SECONDS);
        assertThat(row.getVacationPeriodDays()).isEqualTo(MailboxSettings.MIN_PERIOD_DAYS);
    }

    // ------------------------------------------------------------------ the signature

    @Test
    @DisplayName("a signature is rebuilt through the outbound allowlist, not merely stored")
    void theSignatureIsSanitised() {
        save(Map.of("signatureHtml",
                "<p>Priya Sharma<script>fetch('/steal')</script>"
                        + "<a href=\"javascript:alert(1)\">site</a>"
                        + "<a href=\"https://jarurat.care\">jarurat.care</a></p>"));

        String kept = stored.get(SUPPORT).getSignatureHtml();
        assertThat(kept).doesNotContain("script").doesNotContain("javascript:");
        assertThat(kept).contains("Priya Sharma").contains("https://jarurat.care");
    }

    @Test
    @DisplayName("the block the composer appends carries the standard separator")
    void theSeparatorIsServerSide() {
        save(Map.of("signatureHtml", "<p>Priya Sharma</p>", "signatureOnNew", "true",
                "signatureOnReply", "false"));

        Map<String, Object> out = api.read(null, session);
        assertThat(String.valueOf(out.get("signatureForNew"))).startsWith("<div>--&#32;</div>");
        assertThat(out.get("signatureForReply")).isEqualTo("");
    }

    @Test
    @DisplayName("a blank signature produces no separator floating on its own")
    void noSignatureMeansNoBlock() {
        save(Map.of("signatureHtml", "", "signatureOnNew", "true"));

        assertThat(api.read(null, session).get("signatureForNew")).isEqualTo("");
    }

    // ------------------------------------------------------------------ out of office

    @Test
    @DisplayName("saving an out of office pushes it to the mail server under the vacation capability")
    void theVacationGoesToTheServer() {
        save(Map.of("vacationEnabled", "true",
                "vacationSubject", "Away until 20 September",
                "vacationHtml", "<p>I am at a camp and will reply on the 21st.</p>",
                "vacationFrom", "2026-09-10T00:00:00Z",
                "vacationTo", "2026-09-20T00:00:00Z",
                "vacationPeriodDays", "7"));

        assertThat(jmapUsing).isNotEmpty();
        assertThat(jmapUsing.get(0)).contains(MailSettingsApi.VACATION);

        ObjectNode args = vacationArgs();
        JsonNode patch = args.path("update").path("singleton");
        assertThat(patch.path("isEnabled").asBoolean()).isTrue();
        assertThat(patch.path("subject").asString()).isEqualTo("Away until 20 September");
        assertThat(patch.path("htmlBody").asString()).contains("camp");
        // The text alternative is derived rather than stored, so it can never drift.
        assertThat(patch.path("textBody").asString()).contains("camp");
        assertThat(patch.path("fromDate").asString()).isEqualTo("2026-09-10T00:00:00Z");
        assertThat(patch.path("toDate").asString()).isEqualTo("2026-09-20T00:00:00Z");

        MailboxSettings row = stored.get(SUPPORT);
        assertThat(row.isVacationServerSide()).isTrue();
        assertThat(row.getVacationServerNote()).contains("mail server is answering");
    }

    @Test
    @DisplayName("a mail server without the capability still saves, and says it is not answering")
    void aServerThatRefusesIsReportedRatherThanHidden() {
        when(jmap.call(anyString(), anyList(), any())).thenThrow(
                new MailException(MailException.Kind.PROTOCOL,
                        "VacationResponse/set failed: unknownCapability"));

        Map<String, Object> out = save(Map.of("vacationEnabled", "true",
                "vacationHtml", "<p>Away.</p>",
                "signatureHtml", "<p>Priya</p>"));

        assertThat(out.get("vacationServerSide")).isEqualTo(false);
        assertThat(String.valueOf(out.get("vacationServerNote"))).contains("unknownCapability");
        // The point of saving first: an outage must not cost somebody their signature.
        assertThat(stored.get(SUPPORT).getSignatureHtml()).contains("Priya");
        assertThat(stored.get(SUPPORT).isVacationEnabled()).isTrue();
    }

    @Test
    @DisplayName("switching the out of office on again starts every sender period over")
    void aFreshAbsenceForgetsTheOldLedger() {
        MailboxSettings row = new MailboxSettings(SUPPORT);
        row.setVacationEnabled(false);
        row.claimAutoReply("donor@example.org", false, false, java.time.Instant.now());
        stored.put(SUPPORT, row);

        save(Map.of("vacationEnabled", "true", "vacationHtml", "<p>Away again.</p>"));

        assertThat(stored.get(SUPPORT).autoRepliedCount()).isZero();
    }

    @Test
    @DisplayName("an out of office with no message is refused rather than sent blank")
    void ablankAutoReplyIsRefused() {
        assertThatThrownBy(() -> save(Map.of("vacationEnabled", "true", "vacationHtml", "")))
                .isInstanceOf(MailException.class)
                .hasMessageContaining("blank automatic reply");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("dates the wrong way round are refused, because an absence nobody is told about is worse")
    void backwardsDatesAreRefused() {
        assertThatThrownBy(() -> save(Map.of("vacationEnabled", "true",
                "vacationHtml", "<p>Away.</p>",
                "vacationFrom", "2026-09-20T00:00:00Z",
                "vacationTo", "2026-09-10T00:00:00Z")))
                .isInstanceOf(MailException.class)
                .hasMessageContaining("ends before it starts");
    }

    @Test
    @DisplayName("a date that does not parse is refused rather than read as now")
    void agarbledDateIsRefused() {
        assertThatThrownBy(() -> save(Map.of("vacationFrom", "next tuesday")))
                .isInstanceOf(MailException.class)
                .hasMessageContaining("start date");
    }

    // ------------------------------------------------------------------ honesty

    @Test
    @DisplayName("the two preferences with no consumer say so rather than promising")
    void unhonouredPreferencesAreDeclared() {
        Map<String, Object> out = api.read(null, session);

        assertThat(out.get("undoSendHonoured")).isEqualTo(false);
        assertThat(out.get("readReceiptHonoured")).isEqualTo(false);
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode vacationArgs() {
        ArrayNode calls = jmapRequests.get(jmapRequests.size() - 1);
        return (ObjectNode) calls.get(0).get(1);
    }

    private Map<String, Object> save(Map<String, String> form) {
        return api.save(null, session,
                form.get("signatureHtml"), form.get("signatureOnNew"), form.get("signatureOnReply"),
                form.get("vacationEnabled"), form.get("vacationSubject"), form.get("vacationHtml"),
                form.get("vacationFrom"), form.get("vacationTo"), form.get("vacationPeriodDays"),
                form.get("preferHtml"), form.get("loadRemoteImages"), form.get("messagesPerPage"),
                form.get("readingPane"), form.get("undoSendSeconds"), form.get("defaultReply"),
                form.get("requestReadReceipt"));
    }
}
