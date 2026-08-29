package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.Attachment;
import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the compose screen is allowed to hand the mail server, and what it is not.
 *
 * Two of these are the difference between a mailer and a delivery mechanism for
 * malware. A .exe or a .js reaching a recipient is one double click from running on
 * their machine, and no serious provider carries either, so a mailer that does is
 * both a hazard to the people it writes to and a fast route onto every blocklist
 * that matters. The other is arithmetic that is silent when it is wrong: base64
 * inflates an attachment by about 1.37, so the 25MB the upload path accepts leaves
 * this box as 34MB and is refused by the receiving server long after we told the
 * sender it had gone, filed a copy in Sent and wrote a SENT row in the message log.
 *
 * Both are asserted at the controller, because both have to be decided before a
 * single byte is offered to the blob store: a refusal that happens after the upload
 * has already run costs the sender the upload anyway, and on a phone that is the
 * whole of what they were waiting for.
 */
class MailAttachmentSendTest {

    private static final String MAILBOX = "priya@jarurat.care";
    private static final long CAP = 17_825_792L;               // 17MiB, the shipped default

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
        when(mail.maxAttachmentBytes()).thenReturn(CAP);
    }

    // ------------------------------------------------------------------ type refusal

    /**
     * The nine every provider refuses. Each is a file the recipient's own operating
     * system will execute rather than open, which is the whole distinction: a PDF is
     * handed to a reader, an .exe is handed to the CPU.
     */
    @ParameterizedTest
    @ValueSource(strings = {"setup.exe", "invoice.scr", "run.bat", "go.cmd", "old.com",
            "photo.pif", "helper.js", "macro.vbs", "tool.jar"})
    @DisplayName("an executable attachment is refused by name and never uploaded")
    void executablesAreRefused(String filename) {
        ResponseEntity<?> answer = send(file(filename, "application/octet-stream", 2048));

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(error(answer))
                .contains(filename)
                .contains("runs as a program");

        // The refusal is worth nothing if the bytes went up anyway, and worth less
        // than nothing if the message went out with them on it.
        verify(jmap, never()).upload(anyString(), anyString(), anyLong(), any());
        verify(mail, never()).send(anyString(), any(Outgoing.class));
    }

    /**
     * report.pdf.exe is the oldest trick there is, and it works because Windows
     * hides known extensions, so the recipient sees "report.pdf" on a file that is
     * an executable. Only the last extension decides what runs.
     */
    @Test
    @DisplayName("a double extension is judged on the last one")
    void doubleExtensionIsRefused() {
        ResponseEntity<?> answer = send(file("quarterly-report.pdf.exe", "application/pdf", 4096));

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(error(answer)).contains(".exe");
    }

    /**
     * Windows strips trailing dots and spaces when it resolves a path, so
     * "payload.exe." saves and runs as payload.exe. Reading the extension without
     * stripping them first would let that straight through.
     */
    @Test
    @DisplayName("a trailing dot does not hide an executable")
    void trailingDotIsRefused() {
        assertThat(error(send(file("payload.exe.", "application/octet-stream", 512)))).contains(".exe");
        assertThat(error(send(file("payload.exe ", "application/octet-stream", 512)))).contains(".exe");
    }

    /** The declared MIME type is whatever the poster felt like, so it cannot be the thing checked. */
    @Test
    @DisplayName("an executable claiming to be a PDF is still refused")
    void aLyingContentTypeDoesNotHelp() {
        assertThat(error(send(file("update.exe", "application/pdf", 900)))).contains("update.exe");
    }

    @Test
    @DisplayName("the documents this mailer exists to carry are not refused")
    void ordinaryFilesGoThrough() {
        blobFor("report.pdf");
        ResponseEntity<?> answer = send(file("report.pdf", "application/pdf", 200_000));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        verify(jmap).upload(eq(MAILBOX), eq("application/pdf"), eq(200_000L), any());
    }

    // ------------------------------------------------------------------ size limit

    /**
     * One byte over is over. The message names both numbers because the sender is
     * looking at the file size on their disk and the limit is enforced on the
     * encoded size, and without the second number the rule looks arbitrary.
     */
    @Test
    @DisplayName("one byte over the budget is refused, with the encoded size spelled out")
    void oversizeIsRefusedBeforeAnythingIsUploaded() {
        ResponseEntity<?> answer = send(file("scan.tiff", "image/tiff", CAP + 1));

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(error(answer)).contains("17.0 MB").contains("23.3 MB");
        verify(jmap, never()).upload(anyString(), anyString(), anyLong(), any());
        verify(mail, never()).send(anyString(), any(Outgoing.class));
    }

    /** The budget is the total, not the largest file. Four files under it can still be over it. */
    @Test
    @DisplayName("the limit is the sum of the files, not each one")
    void theBudgetIsShared() {
        ResponseEntity<?> answer = send(
                file("a.pdf", "application/pdf", 9_000_000),
                file("b.pdf", "application/pdf", 9_000_000));

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(error(answer)).contains("17.2 MB");
        verify(jmap, never()).upload(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("exactly the budget is allowed")
    void theBoundaryItselfSends() {
        blobFor("big.zip");
        ResponseEntity<?> answer = send(file("big.zip", "application/zip", CAP));
        assertThat(answer.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("more files than one message may carry is refused as a count, not a size")
    void tooManyFilesIsRefused() {
        MultipartFile[] many = new MultipartFile[21];
        for (int i = 0; i < many.length; i++) many[i] = file("page-" + i + ".png", "image/png", 1000);

        ResponseEntity<?> answer = send(many);
        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(error(answer)).contains("21 files");
    }

    // ------------------------------------------------------------------ the happy path

    /**
     * The blob id the upload returned is the one the message references. Getting
     * this wrong sends a message whose attachment is somebody else's file, or none.
     */
    @Test
    @DisplayName("the uploaded blob is what the message carries")
    void theBlobReachesTheSend() {
        when(jmap.upload(eq(MAILBOX), anyString(), anyLong(), any()))
                .thenReturn(new JmapClient.Blob("Gd1a2b", "application/pdf", 4096));

        ResponseEntity<?> answer = send(file("consent form.pdf", "application/pdf", 4096));
        assertThat(answer.getStatusCode().value()).isEqualTo(200);

        ArgumentCaptor<Outgoing> captor = ArgumentCaptor.forClass(Outgoing.class);
        verify(mail).send(eq(MAILBOX), captor.capture());

        List<Attachment> carried = captor.getValue().attachments();
        assertThat(carried).hasSize(1);
        assertThat(carried.get(0).blobId()).isEqualTo("Gd1a2b");
        assertThat(carried.get(0).safeName()).isEqualTo("consent form.pdf");
        assertThat(carried.get(0).disposition()).isEqualTo("attachment");
        assertThat(body(answer).get("message").toString()).contains("1 file");
    }

    /**
     * The contract every other caller relies on. A send that names only a recipient,
     * a subject and a body has to keep answering exactly what it used to, which is the
     * whole reason the blind copy, formatted HTML and threading parameters were added
     * as optional ones rather than folded into the existing four.
     */
    @Test
    @DisplayName("a send with no files is untouched")
    void theOldShapeStillWorks() {
        ResponseEntity<?> answer = controller.send(null, session,
                "asha@jarurat.care", null, null, "Hello", "Body text", null, null, null, null);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(body(answer).get("message").toString()).isEqualTo("Sent to asha@jarurat.care.");

        ArgumentCaptor<Outgoing> message = ArgumentCaptor.forClass(Outgoing.class);
        verify(mail).send(eq(MAILBOX), message.capture());
        assertThat(message.getValue().to()).containsExactly("asha@jarurat.care");
        assertThat(message.getValue().cc()).isEmpty();
        assertThat(message.getValue().bcc()).isEmpty();
        assertThat(message.getValue().subject()).isEqualTo("Hello");
        assertThat(message.getValue().text()).isEqualTo("Body text");
        assertThat(message.getValue().html()).isEqualTo("<p>Body text</p>");
        verify(jmap, never()).upload(anyString(), anyString(), anyLong(), any());
    }

    /** An input submitted with nothing chosen arrives as an empty part and is not a file. */
    @Test
    @DisplayName("an empty part is not an attachment")
    void emptyPartsAreIgnored() {
        ResponseEntity<?> answer = send(new MockMultipartFile("files", "", null, new byte[0]));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(body(answer).get("message").toString()).doesNotContain("file");
        verify(jmap, never()).upload(anyString(), anyString(), anyLong(), any());
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<?> send(MultipartFile... files) {
        return controller.send(null, session, "asha@jarurat.care", null, null,
                "Subject", "Body", null, null, null, files);
    }

    /**
     * Size without bytes. A MockMultipartFile holding 17MiB of real array would make
     * this suite allocate hundreds of megabytes for assertions about arithmetic, and
     * nothing under test reads the content: the size decides the refusal and the
     * stream is only opened on the upload path these cases never reach.
     */
    private static MultipartFile file(String name, String type, long size) {
        return new MockMultipartFile("files", name, type, new byte[0]) {
            @Override
            public long getSize() { return size; }

            @Override
            public boolean isEmpty() { return size == 0; }

            @Override
            public InputStream getInputStream() {
                return new java.io.ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private void blobFor(String name) {
        when(jmap.upload(anyString(), anyString(), anyLong(), any(Supplier.class)))
                .thenReturn(new JmapClient.Blob("blob-" + name, "application/octet-stream", 1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> answer) {
        return (Map<String, Object>) answer.getBody();
    }

    private static String error(ResponseEntity<?> answer) {
        Object message = body(answer).get("error");
        return message == null ? "" : message.toString();
    }
}
