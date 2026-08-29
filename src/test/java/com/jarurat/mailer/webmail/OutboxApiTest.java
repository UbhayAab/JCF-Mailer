package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.mail.MailService;
import com.jarurat.mailer.mail.Outgoing;
import com.jarurat.mailer.models.QueuedMessage;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the outbox endpoint accepts, and what it says when it will not.
 *
 * The wording assertions are the ones that earn their place. A person who has just
 * watched an Undo button fail needs to be told that mail cannot be pulled back out of
 * somebody else's server, not that "the operation could not be completed", and the
 * difference between those two sentences is the whole difference between an honest
 * feature and one that will be discovered to be a lie at the worst possible moment.
 * So these assert on the words.
 */
class OutboxApiTest {

    private static final String MAILBOX = "priya@jarurat.care";

    private final OutboxService outbox = mock(OutboxService.class);
    private final MailService mail = mock(MailService.class);
    private final MailboxAccess mailbox = mock(MailboxAccess.class);
    private final JmapClient jmap = mock(JmapClient.class);

    private final OutboxApi api = new OutboxApi(outbox, mail, mailbox, jmap);

    private final HttpSession session = new MockHttpSession();

    @BeforeEach
    void mailboxIsOpen() {
        when(mailbox.require(any(), any())).thenReturn(MAILBOX);
        when(mail.maxAttachmentBytes()).thenReturn(17_825_792L);
        when(outbox.undoSeconds()).thenReturn(10);
        when(outbox.maxDaysAhead()).thenReturn(60);
        when(outbox.attachmentHours()).thenReturn(24);
        when(outbox.queue(anyString(), any(Outgoing.class), any(), any()))
                .thenAnswer(call -> row(call.getArgument(1), call.getArgument(2)));
    }

    private static QueuedMessage row(Outgoing message, LocalDateTime sendAt) {
        LocalDateTime now = LocalDateTime.now();
        return new QueuedMessage(MAILBOX,
                sendAt == null ? QueuedMessage.UNDO : QueuedMessage.SCHEDULED,
                sendAt == null ? now.plusSeconds(10) : sendAt, now, message, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    // ------------------------------------------------------------------ queueing

    @Test
    @DisplayName("an ordinary send is held, and the answer says for how long")
    void anOrdinarySendIsHeld() {
        ResponseEntity<?> response = api.queue(null, session, "dr.rao@example.org",
                null, null, "Camp", "Dear Dr Rao,", null, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = bodyOf(response);
        assertThat(body.get("kind")).isEqualTo(QueuedMessage.UNDO);
        assertThat(body.get("undoSeconds")).isEqualTo(10);
        assertThat(String.valueOf(body.get("message"))).contains("Sending in 10 seconds");
        assertThat(String.valueOf(body.get("message"))).contains("can be stopped");
        assertThat(body).containsKey("cancelUntil");
    }

    @Test
    @DisplayName("the mailbox comes from the session and never from the request")
    void theMailboxIsTheSessionMailbox() {
        api.queue(null, session, "dr.rao@example.org", null, null, "Camp", "Hello", null, null, null, null);

        verify(outbox).queue(eq(MAILBOX), any(Outgoing.class), any(), any());
    }

    @Test
    @DisplayName("a scheduled time is read as an instant and passed through whole")
    void aScheduledTimeIsRead() {
        api.queue(null, session, "dr.rao@example.org", null, null, "Camp", "Hello", null, null,
                "2026-09-01T09:00:00Z", null);

        ArgumentCaptor<LocalDateTime> when = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outbox).queue(eq(MAILBOX), any(Outgoing.class), when.capture(), any());
        assertThat(when.getValue()).isNotNull();
        assertThat(when.getValue().getNano()).isZero();
    }

    @Test
    @DisplayName("a time this server cannot read is refused before anything is queued")
    void anUnreadableTimeIsRefused() {
        try {
            api.queue(null, session, "dr.rao@example.org", null, null, "Camp", "Hello", null, null,
                    "next tuesday", null);
            org.junit.jupiter.api.Assertions.fail("an unreadable time should be refused");
        } catch (MailException e) {
            assertThat(e.getKind()).isEqualTo(MailException.Kind.PROTOCOL);
            assertThat(e.getMessage()).contains("not a time this server can read");
        }
        verify(outbox, never()).queue(anyString(), any(Outgoing.class), any(), any());
    }

    @Test
    @DisplayName("a mistyped address names the field and quotes the text, and queues nothing")
    void aMistypedAddressIsNamed() {
        try {
            api.queue(null, session, "dr.rao@example.org, broken@", null, null, "Camp", "Hello",
                    null, null, null, null);
            org.junit.jupiter.api.Assertions.fail("a bad address should be refused");
        } catch (MailException e) {
            assertThat(e.getMessage()).isEqualTo("To: \"broken@\" is not an email address.");
        }
        verify(outbox, never()).queue(anyString(), any(Outgoing.class), any(), any());
    }

    @Test
    @DisplayName("blind copies reach the queued message and are not folded into To")
    void blindCopiesAreCarried() {
        api.queue(null, session, "dr.rao@example.org", "sister@example.org", "audit@jarurat.care",
                "Camp", "Hello", null, null, null, null);

        ArgumentCaptor<Outgoing> sent = ArgumentCaptor.forClass(Outgoing.class);
        verify(outbox).queue(eq(MAILBOX), sent.capture(), any(), any());
        assertThat(sent.getValue().to()).containsExactly("dr.rao@example.org");
        assertThat(sent.getValue().cc()).containsExactly("sister@example.org");
        assertThat(sent.getValue().bcc()).containsExactly("audit@jarurat.care");
    }

    @Test
    @DisplayName("a plain text body still gets the HTML part the immediate send path builds")
    void plainTextGetsTheSameHtmlPart() {
        api.queue(null, session, "dr.rao@example.org", null, null, "Camp",
                "Dear Dr Rao,\n\nThe camp is confirmed.", null, null, null, null);

        ArgumentCaptor<Outgoing> sent = ArgumentCaptor.forClass(Outgoing.class);
        verify(outbox).queue(eq(MAILBOX), sent.capture(), any(), any());
        assertThat(sent.getValue().html()).isEqualTo("<p>Dear Dr Rao,</p><p>The camp is confirmed.</p>");
    }

    // ------------------------------------------------------------------ cancelling

    @Test
    @DisplayName("a cancel that arrives too late is a 409 that says mail cannot be recalled")
    void aLateCancelSaysTheTruth() {
        when(outbox.cancel(MAILBOX, 7L)).thenReturn(OutboxService.Outcome.refused(
                OutboxService.Problem.TOO_LATE,
                "That message has already been sent. Mail cannot be recalled once it has left "
                        + "this server, so the only thing left is to send a correction.", null));

        ResponseEntity<?> response = api.cancel(null, session, 7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> body = bodyOf(response);
        assertThat(body.get("tooLate")).isEqualTo(true);
        assertThat(String.valueOf(body.get("error"))).contains("cannot be recalled");
    }

    @Test
    @DisplayName("somebody else's message is a 404 and never a hint that it exists")
    void anotherMailboxesMessageIsNotFound() {
        when(outbox.cancel(MAILBOX, 7L)).thenReturn(OutboxService.Outcome.refused(
                OutboxService.Problem.NOT_FOUND, "There is no such message in your outbox.", null));

        ResponseEntity<?> response = api.cancel(null, session, 7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(String.valueOf(bodyOf(response).get("error"))).isEqualTo("There is no such message in your outbox.");
    }

    @Test
    @DisplayName("a cancel inside the window answers ok and says nothing was sent")
    void anEarlyCancelSucceeds() {
        when(outbox.cancel(MAILBOX, 7L)).thenReturn(OutboxService.Outcome.done(
                "That message was stopped and nothing was sent.", null));

        ResponseEntity<?> response = api.cancel(null, session, 7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyOf(response).get("ok")).isEqualTo(true);
        assertThat(String.valueOf(bodyOf(response).get("message"))).contains("nothing was sent");
    }

    // ------------------------------------------------------------------ listing

    @Test
    @DisplayName("the listing carries the failure count, which is what makes a 3am failure visible")
    void theListingCarriesFailures() {
        QueuedMessage failed = row(Outgoing.message(List.of("dr.rao@example.org"), List.of(),
                "Camp", "<p>Hello</p>", "Hello"), null);
        failed.setState(QueuedMessage.FAILED);
        failed.setLastError("The mail server could not be reached.");
        when(outbox.open(MAILBOX)).thenReturn(List.of(failed));
        when(outbox.unseenFailures(MAILBOX)).thenReturn(1L);

        Map<String, Object> body = api.list(null, session);

        assertThat(body.get("failures")).isEqualTo(1L);
        assertThat(body.get("undoSeconds")).isEqualTo(10);
        assertThat(body.get("mailbox")).isEqualTo(MAILBOX);
        List<?> rows = (List<?>) body.get("messages");
        assertThat(rows).hasSize(1);
        assertThat(((Map<?, ?>) rows.get(0)).get("state")).isEqualTo(QueuedMessage.FAILED);
        assertThat(((Map<?, ?>) rows.get(0)).get("cancellable")).isEqualTo(false);
        assertThat(String.valueOf(((Map<?, ?>) rows.get(0)).get("error"))).contains("could not be reached");
    }

    @Test
    @DisplayName("a locked mailbox is a 409 that asks for the mailbox and not a 401 to the login page")
    void aLockedMailboxAsksForTheMailbox() {
        when(mailbox.require(any(), any()))
                .thenThrow(new MailboxAccess.MailboxLockedException("Open your mailbox to read mail on this device."));

        try {
            api.cancel(null, session, 7L);
            org.junit.jupiter.api.Assertions.fail("a locked mailbox should refuse");
        } catch (MailboxAccess.MailboxLockedException e) {
            ResponseEntity<?> response = api.onLocked(e);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(bodyOf(response).get("locked")).isEqualTo(true);
        }
    }
}
