package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.mail.MailService;
import com.jarurat.mailer.messagelog.MessageLogService;
import com.jarurat.mailer.security.LoginRateLimiter;
import com.jarurat.mailer.services.AuditService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The unlock prompt as a password oracle, which is what it was.
 *
 * POST /api/mail/unlock offers an address and a password to the mail server and says
 * within one round trip whether the pair was accepted. It had no limit of any kind on
 * how often, and it wrote an audit row only when an open succeeded, so a sweep of
 * every address in the organisation that guessed wrong every time left nothing behind
 * at all. Being behind a console session raises the price of starting and does nothing
 * about the rate once anybody is in, and a mailbox password is exactly the thing a
 * signed-in mail-only session does not hold for anybody else.
 *
 * Both halves are asserted here, because both were absent and each is silent on its
 * own: an unlimited endpoint looks like a working endpoint, and a log with no failure
 * rows looks like a quiet week.
 */
class MailboxUnlockLimitTest {

    private static final String ADDRESS = "priya@jarurat.care";

    private final MailService mail = mock(MailService.class);
    private final MailboxAccess mailbox = mock(MailboxAccess.class);
    private final AuditService audit = mock(AuditService.class);
    private final MessageLogService messageLog = mock(MessageLogService.class);
    private final JmapClient jmap = mock(JmapClient.class);
    private final LoginRateLimiter limiter = new LoginRateLimiter();

    private final MailApiController controller =
            new MailApiController(mail, mailbox, audit, messageLog, jmap, limiter);

    @Test
    @DisplayName("a refused mailbox password leaves a row behind")
    void theFailurePathIsAudited() {
        HttpSession session = new MockHttpSession();
        when(mailbox.open(any(), eq(ADDRESS), eq("guess")))
                .thenThrow(new MailException(MailException.Kind.AUTH, "That mailbox password was not accepted."));

        assertThatThrownBy(() -> controller.unlock(session, request(), ADDRESS, "guess"))
                .isInstanceOf(MailException.class);

        verify(audit).record(eq("MAILBOX_OPEN_FAILED"), eq(ADDRESS), anyString());
    }

    @Test
    @DisplayName("a sweep of the domain is refused once the client has spent its budget")
    void theSweepRunsOutOfBudget() {
        HttpSession session = new MockHttpSession();
        when(mailbox.open(any(), anyString(), anyString()))
                .thenThrow(new MailException(MailException.Kind.AUTH, "no"));

        // Distinct addresses, which is what a sweep through the staff list looks like
        // and which no per-address control can see. The client cap is the one that
        // notices. The exact size of that cap is LoginRateLimiterTest's business; what
        // has to be true here is that there is one at all and that the sweep runs into
        // it long before it has been through a staff list.
        int asked = 0;
        ResponseEntity<?> refused = null;
        for (int i = 0; i < 500 && refused == null; i++) {
            String address = "person" + i + "@jarurat.care";
            try {
                ResponseEntity<?> answer = controller.unlock(session, request(), address, "guess");
                if (answer.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) refused = answer;
            } catch (MailException expected) {
                asked++;
            }
        }

        assertThat(refused).as("the sweep was never refused").isNotNull();
        assertThat(refused.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(asked).isLessThan(60);
        // Once refused, the mail server is not asked again: the refusal happens before
        // the oracle answers rather than after it.
        controller.unlock(session, request(), "later@jarurat.care", "guess");
        verify(mailbox, times(asked)).open(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("opening a mailbox still works, is audited, and refunds the address")
    void theSuccessPathIsUnchanged() {
        HttpSession session = new MockHttpSession();
        when(mailbox.open(any(), eq(ADDRESS), eq("right"))).thenReturn(ADDRESS);

        ResponseEntity<?> opened = controller.unlock(session, request(), ADDRESS, "right");

        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(opened.getBody()).isEqualTo(java.util.Map.of("ok", true, "mailbox", ADDRESS));
        verify(audit).record("MAILBOX_OPENED", ADDRESS, "webmail session");
        // Refunded, so somebody who mistypes their mailbox password and then gets it
        // right does not carry a delay into the login form afterwards.
        assertThat(limiter.reserve(ADDRESS, "198.51.100.3").delayMillis()).isZero();
    }

    /** One office, one nginx, one peer address, which is what the cap is keyed on. */
    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mail/unlock");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 203.0.113.9");
        return request;
    }
}
