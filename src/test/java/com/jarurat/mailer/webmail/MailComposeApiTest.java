package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailAddress;
import com.jarurat.mailer.mail.MailService;
import com.jarurat.mailer.mail.MessageBody;
import com.jarurat.mailer.mail.Outgoing;
import com.jarurat.mailer.messagelog.MessageLogService;
import com.jarurat.mailer.security.LoginRateLimiter;
import com.jarurat.mailer.services.AuditService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the compose screen may hand this API, and what it gets told when it is wrong.
 *
 * The address checks are the ones that earn their place. A person typing eleven
 * addresses into a To field and getting back "invalid recipient" has to read all
 * eleven to find the one with a missing letter, and what they actually do instead is
 * retype the lot, which is how a wrong address reaches a donor. So the failure names
 * the field and quotes the exact text, and these assert on that wording rather than
 * only on the status code.
 */
class MailComposeApiTest {

    private static final String MAILBOX = "priya@jarurat.care";

    private final MailService mail = mock(MailService.class);
    private final MailboxAccess mailbox = mock(MailboxAccess.class);
    private final AuditService audit = mock(AuditService.class);
    private final MessageLogService messageLog = mock(MessageLogService.class);
    private final JmapClient jmap = mock(JmapClient.class);
    private final LoginRateLimiter limiter = new LoginRateLimiter();

    private final MailApiController controller =
            new MailApiController(mail, mailbox, audit, messageLog, jmap, limiter);

    private final HttpSession session = new MockHttpSession();

    @BeforeEach
    void mailboxIsOpen() {
        when(mailbox.require(any(), any())).thenReturn(MAILBOX);
        when(mail.maxAttachmentBytes()).thenReturn(17_825_792L);
        when(mail.send(anyString(), any(Outgoing.class))).thenReturn("email-1");
        when(mail.saveDraft(anyString(), any(), any(Outgoing.class))).thenReturn("draft-2");
    }

    // ------------------------------------------------------------------ address validation

    @Test
    @DisplayName("a malformed address is refused by field and by the exact text typed")
    void aBadAddressIsNamed() {
        ResponseEntity<?> answer = send(
                "good@example.org, broken@, also-good@example.org",
                null, null, "Subject", "Body", null, null, null, null);

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(error(answer)).isEqualTo("To: \"broken@\" is not an email address.");
        verify(mail, never()).send(anyString(), any(Outgoing.class));
    }

    @ParameterizedTest
    @DisplayName("the failure names the field the address was typed into")
    @ValueSource(strings = {"To", "Cc", "Bcc"})
    void theFieldIsNamed(String field) {
        String bad = "not an address";
        ResponseEntity<?> answer = send(
                field.equals("To") ? bad : "a@example.org",
                field.equals("Cc") ? bad : null,
                field.equals("Bcc") ? bad : null,
                "s", "b", null, null, null, null);

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(error(answer)).startsWith(field + ": \"not an address\"");
    }

    @Test
    @DisplayName("a pasted display name is normalised rather than refused")
    void displayNamesAreAccepted() {
        send(
                "Priya Sharma <Priya@Jarurat.Care>; Dr Rao <rao@TMC.gov.in>",
                null, null, "s", "b", null, null, null, null);

        Outgoing sent = captureSend();
        assertThat(sent.to()).containsExactly("Priya@jarurat.care", "rao@tmc.gov.in");
    }

    @Test
    @DisplayName("the same address typed twice is sent once")
    void duplicatesAreCollapsed() {
        send( "a@example.org, A@Example.org", null, null,
                "s", "b", null, null, null, null);
        assertThat(captureSend().to()).containsExactly("a@example.org");
    }

    @Test
    @DisplayName("past the recipient cap the refusal points at Campaign Studio")
    void theRecipientCapIsEnforcedAndExplained() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= Outgoing.MAX_RECIPIENTS; i++) many.append("p").append(i).append("@example.org,");

        ResponseEntity<?> answer = send( many.toString(), null, null,
                "s", "b", null, null, null, null);

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(error(answer)).contains("Campaign Studio").contains(String.valueOf(Outgoing.MAX_RECIPIENTS));
        verify(mail, never()).send(anyString(), any(Outgoing.class));
    }

    @Test
    @DisplayName("a message with nobody to send it to is refused")
    void noRecipientIsRefused() {
        ResponseEntity<?> answer = send( "", null, null,
                "s", "b", null, null, null, null);
        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(error(answer)).isEqualTo("Add at least one recipient.");
    }

    // ------------------------------------------------------------------ blind copies

    @Test
    @DisplayName("blind copies reach the service as blind copies and are not folded into To or Cc")
    void blindCopiesArriveSeparately() {
        send( "hospital@tmc.gov.in", "finance@jarurat.care",
                "trustee@jarurat.care, auditor@example.org", "s", "b", null, null, null, null);

        Outgoing sent = captureSend();
        assertThat(sent.to()).containsExactly("hospital@tmc.gov.in");
        assertThat(sent.cc()).containsExactly("finance@jarurat.care");
        assertThat(sent.bcc()).containsExactly("trustee@jarurat.care", "auditor@example.org");
    }

    /**
     * The Sent copy carries no Bcc header by construction, so this log is the only
     * surviving record of who a blind copy went to. Losing it would mean the
     * organisation could not answer "who did we tell" about its own mail.
     */
    @Test
    @DisplayName("every blind copy is written to the message log and the audit trail")
    void blindCopiesAreRecordedInternally() {
        send( "hospital@tmc.gov.in", null,
                "trustee@jarurat.care", "Camp", "b", null, null, null, null);

        verify(messageLog).record(any(), any(), eq("trustee@jarurat.care"), anyString(),
                any(), any(), any(), anyString(), anyString(), any(Long.class), anyString());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("MAIL_SENT"), anyString(), detail.capture());
        assertThat(detail.getValue()).contains("bcc trustee@jarurat.care");
    }

    @Test
    @DisplayName("the confirmation names the visible recipients and only counts the blind ones")
    void theConfirmationDoesNotEchoBlindAddresses() {
        ResponseEntity<?> answer = send( "hospital@tmc.gov.in", null,
                "trustee@jarurat.care, auditor@example.org", "s", "b", null, null, null, null);

        String message = String.valueOf(((Map<?, ?>) answer.getBody()).get("message"));
        assertThat(message).isEqualTo("Sent to hospital@tmc.gov.in and 2 blind copies.");
    }

    // ------------------------------------------------------------------ html and text

    @Test
    @DisplayName("with no html parameter the server builds the HTML part exactly as it always did")
    void plainTextSendIsUnchanged() {
        send( "a@example.org", null, null, "s",
                "First line\n\nSecond line", null, null, null, null);

        Outgoing sent = captureSend();
        assertThat(sent.html()).isEqualTo("<p>First line</p><p>Second line</p>");
        assertThat(sent.text()).isEqualTo("First line\n\nSecond line");
    }

    @Test
    @DisplayName("supplied html is passed through with the typed text as the plain alternative")
    void htmlAndTextTravelTogether() {
        send( "a@example.org", null, null, "s",
                "Hello there", "<p>Hello <strong>there</strong></p>", null, null, null);

        Outgoing sent = captureSend();
        assertThat(sent.html()).isEqualTo("<p>Hello <strong>there</strong></p>");
        assertThat(sent.text()).isEqualTo("Hello there");
    }

    @Test
    @DisplayName("html past the ceiling is refused with a sentence about why")
    void oversizedHtmlIsRefused() {
        ResponseEntity<?> answer = send( "a@example.org", null, null,
                "s", "b", "x".repeat(70_000), null, null, null);

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(error(answer)).contains("Gmail");
        verify(mail, never()).send(anyString(), any(Outgoing.class));
    }

    // ------------------------------------------------------------------ threading

    /**
     * The browser names the parent message and never the header. A client that could
     * set In-Reply-To directly could staple our mail into the middle of a conversation
     * it was never part of, so the two headers are derived here from what the mail
     * server says the parent is.
     */
    @Test
    @DisplayName("a reply derives its threading headers from the parent, server side")
    void replyHeadersComeFromTheParent() {
        when(mail.getMessage(MAILBOX, "parent-1")).thenReturn(parent());

        send( "rao@tmc.gov.in", null, null, "Re: Camp", "Confirmed",
                null, "parent-1", null, null);

        Outgoing sent = captureSend();
        assertThat(sent.inReplyTo()).isEqualTo("abc@tmc.gov.in");
        assertThat(sent.references()).containsExactly("root@jarurat.care");
    }

    @Test
    @DisplayName("a reply flags the parent as answered, and only after the send succeeded")
    void theParentIsFlaggedAnswered() {
        when(mail.getMessage(MAILBOX, "parent-1")).thenReturn(parent());

        send( "rao@tmc.gov.in", null, null, "Re: Camp", "ok",
                null, "parent-1", null, null);

        verify(mail).setKeyword(MAILBOX, "parent-1", "answered", true);
    }

    @Test
    @DisplayName("a message that is not a reply touches no parent")
    void aNewMessageFlagsNothing() {
        send( "a@example.org", null, null, "s", "b", null, null, null, null);
        verify(mail, never()).setKeyword(anyString(), anyString(), anyString(), anyBoolean());
    }

    // ------------------------------------------------------------------ drafts

    @Test
    @DisplayName("a draft with no recipients at all is still saved, because that is what half a letter looks like")
    void anEmptyDraftIsSaved() {
        ResponseEntity<?> answer = saveDraft( null, "", null, null,
                "Half written", "So far", null, null);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<?, ?>) answer.getBody()).get("id")).isEqualTo("draft-2");
    }

    @Test
    @DisplayName("a draft with a typo in it is refused, so the sender sees it while they are looking at it")
    void aDraftStillValidatesAddresses() {
        ResponseEntity<?> answer = saveDraft( null, "broken@", null, null,
                "s", "b", null, null);
        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(error(answer)).contains("broken@");
    }

    @Test
    @DisplayName("saving over a draft passes the previous id through so it is replaced, not duplicated")
    void resavingPassesThePreviousId() {
        saveDraft( "draft-1", "a@example.org", null, null,
                "s", "b", null, null);
        verify(mail).saveDraft(eq(MAILBOX), eq("draft-1"), any(Outgoing.class));
    }

    @Test
    @DisplayName("sending from a draft throws the draft away, after the send and not before")
    void sendingFromADraftDiscardsIt() {
        send( "a@example.org", null, null, "s", "b", null, null,
                "draft-1", null);
        verify(mail).deleteDraft(MAILBOX, "draft-1");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Calls the controller and then hands a MailException to the very handler Spring
     * would have used, so these assert the status and the sentence a browser actually
     * receives rather than the exception type that produced them.
     */
    private ResponseEntity<?> send(String to, String cc, String bcc, String subject, String body,
                                   String html, String replyTo, String draftId,
                                   org.springframework.web.multipart.MultipartFile[] files) {
        try {
            return controller.send(null, session, to, cc, bcc, subject, body, html, replyTo, draftId, files);
        } catch (com.jarurat.mailer.mail.MailException e) {
            return controller.onMailFailure(e);
        }
    }

    private ResponseEntity<?> saveDraft(String id, String to, String cc, String bcc,
                                        String subject, String body, String html, String replyTo) {
        try {
            return controller.saveDraft(null, session, id, to, cc, bcc, subject, body, html, replyTo);
        } catch (com.jarurat.mailer.mail.MailException e) {
            return controller.onMailFailure(e);
        }
    }

    private Outgoing captureSend() {
        ArgumentCaptor<Outgoing> captor = ArgumentCaptor.forClass(Outgoing.class);
        verify(mail).send(eq(MAILBOX), captor.capture());
        return captor.getValue();
    }

    private static MessageBody parent() {
        return new MessageBody("parent-1", "thread-1", "Camp",
                List.of(new MailAddress("Dr Rao", "rao@tmc.gov.in")), List.of(), List.of(), List.of(), List.of(),
                Instant.now(), Instant.now(), null, "Can you confirm?", List.of(), List.of("mb-inbox"),
                true, false, false, "abc@tmc.gov.in", List.of(), List.of("root@jarurat.care"));
    }

    private static String error(ResponseEntity<?> answer) {
        return String.valueOf(((Map<?, ?>) answer.getBody()).get("error"));
    }
}
