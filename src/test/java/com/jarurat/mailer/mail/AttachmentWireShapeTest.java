package com.jarurat.mailer.mail;

import com.jarurat.mailer.services.SesSender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The JMAP a send with an attachment actually puts on the wire.
 *
 * Everything else about attachments can be asserted against a mock, and a mock will
 * agree with whatever this code believes. What it cannot check is the one thing the
 * mail server has an opinion about, which is the shape of the Email/set: a
 * bodyStructure that is a multipart/mixed of the body part and then the files, parts
 * that carry a blobId and no partId, and no size field, because RFC 8621 marks size
 * server-set and a set that includes it is refused outright with an invalidArguments
 * that names nothing useful. Getting any of those wrong is a send that fails on the
 * live box and passes every test in this suite.
 *
 * So this stands up a real HTTP server that speaks just enough JMAP to answer, and
 * reads back exactly what the client sent it. Nothing here is mocked below the
 * socket. It also proves the upload goes out Content-Length delimited rather than
 * chunked, which is what lets it stream a 17MB file without holding it.
 */
class AttachmentWireShapeTest {

    private HttpServer server;
    private int port;

    /** Every request body the client sent, in order, so the test can read them back. */
    private final List<String> apiCalls = new CopyOnWriteArrayList<>();
    private final List<String> uploadHeaders = new CopyOnWriteArrayList<>();
    private final List<Integer> uploadSizes = new CopyOnWriteArrayList<>();

    private final ObjectMapper json = new ObjectMapper();

    private MailService mail;

    @BeforeEach
    void startStalwartEnough() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        server.createContext("/jmap/session", ex -> answer(ex, """
                {"username":"priya@jarurat.care",
                 "primaryAccounts":{"urn:ietf:params:jmap:mail":"acc-1"},
                 "apiUrl":"https://mail.jarurat.care/jmap/",
                 "downloadUrl":"https://mail.jarurat.care/jmap/download/{accountId}/{blobId}/{name}?accept={type}",
                 "uploadUrl":"https://mail.jarurat.care/jmap/upload/{accountId}/",
                 "state":"s1"}"""));

        server.createContext("/jmap/upload/", ex -> {
            uploadHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Content-Type"))
                    + "|len=" + ex.getRequestHeaders().getFirst("Content-Length")
                    + "|enc=" + ex.getRequestHeaders().getFirst("Transfer-Encoding"));
            uploadSizes.add(ex.getRequestBody().readAllBytes().length);
            answer(ex, "{\"accountId\":\"acc-1\",\"blobId\":\"Gblob-9\","
                    + "\"type\":\"application/pdf\",\"size\":11}");
        });

        server.createContext("/jmap/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            apiCalls.add(body);
            answer(ex, replyFor(body));
        });

        server.start();

        MailCredentialStore credentials = new MailCredentialStore() {
            @Override public Optional<String> secretFor(String user) { return Optional.of("secret"); }
            @Override public void remember(String user, String secret) { }
            @Override public void forget(String user) { }
            @Override public boolean knows(String user) { return true; }
            @Override public Set<String> knownUsers() { return Set.of("priya@jarurat.care"); }
        };

        JmapClient client = new JmapClient(credentials, "http://127.0.0.1:" + port + "/jmap/", 5, 20);
        SesSender ses = mock(SesSender.class);
        when(ses.toPlainText(org.mockito.ArgumentMatchers.anyString())).thenReturn("plain text");
        mail = new MailService(client, ses, 100, 1_000_000, 17_825_792L);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("an attached file becomes a multipart/mixed part carrying a blobId and no size")
    void theEmailSetCarriesTheBlob() {
        mail.send("priya@jarurat.care", List.of("asha@jarurat.care"), List.of(),
                "Discharge summary", "<p>Attached.</p>", "Attached.",
                List.of(Attachment.outgoing("Gblob-9", "discharge summary.pdf", "application/pdf", 11)));

        JsonNode draft = draftFromLastSet();
        JsonNode structure = draft.path("bodyStructure");

        assertThat(structure.path("type").asString()).isEqualTo("multipart/mixed");
        assertThat(structure.path("subParts")).hasSize(2);

        // The body keeps its own alternative underneath the mixed wrapper rather than
        // being flattened next to the files, or a plain text reader sees the HTML.
        JsonNode body = structure.path("subParts").path(0);
        assertThat(body.path("type").asString()).isEqualTo("multipart/alternative");
        assertThat(body.path("subParts")).hasSize(2);

        JsonNode file = structure.path("subParts").path(1);
        assertThat(file.path("blobId").asString()).isEqualTo("Gblob-9");
        assertThat(file.path("type").asString()).isEqualTo("application/pdf");
        assertThat(file.path("name").asString()).isEqualTo("discharge summary.pdf");
        assertThat(file.path("disposition").asString()).isEqualTo("attachment");
        // Both of these are refusals from the server if they are present.
        assertThat(file.has("size")).isFalse();
        assertThat(file.has("partId")).isFalse();
    }

    /**
     * The no-attachment send is the one every other caller makes, and it must go out
     * byte for byte as it did before any of this existed: bodyStructure straight to
     * the alternative, with no mixed wrapper around it.
     */
    @Test
    @DisplayName("a send with no files is the same request it always was")
    void thePlainSendIsUnchanged() {
        mail.send("priya@jarurat.care", List.of("asha@jarurat.care"), List.of(),
                "Hello", "<p>Hi.</p>", "Hi.");

        JsonNode structure = draftFromLastSet().path("bodyStructure");
        assertThat(structure.path("type").asString()).isEqualTo("multipart/alternative");
        assertThat(structure.has("subParts")).isTrue();
        assertThat(structure.path("subParts")).hasSize(2);
    }

    /** A text-only send still has no mixed wrapper and still names one plain part. */
    @Test
    @DisplayName("a plain text send with a file still wraps exactly once")
    void textOnlyWithAFile() {
        mail.send("priya@jarurat.care", List.of("asha@jarurat.care"), List.of(),
                "Scan", null, "Here it is.",
                List.of(Attachment.outgoing("Gblob-9", "scan.jpg", "image/jpeg", 11)));

        JsonNode structure = draftFromLastSet().path("bodyStructure");
        assertThat(structure.path("type").asString()).isEqualTo("multipart/mixed");
        assertThat(structure.path("subParts").path(0).path("type").asString()).isEqualTo("text/plain");
        assertThat(structure.path("subParts").path(0).path("partId").asString()).isEqualTo("t");
        assertThat(structure.path("subParts").path(1).path("blobId").asString()).isEqualTo("Gblob-9");
    }

    /**
     * Content-Length and not Transfer-Encoding: chunked. ofInputStream on its own
     * reports an unknown length, and the whole point of wrapping it in fromPublisher
     * with the size is that the upload streams off disk and still announces how long
     * it is. A chunked body is the symptom that the wrapper was dropped.
     */
    @Test
    @DisplayName("the upload streams with a declared length and the declared type")
    void theUploadIsLengthDelimited() {
        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
        JmapClient client = new JmapClient(new MailCredentialStore() {
            @Override public Optional<String> secretFor(String user) { return Optional.of("secret"); }
            @Override public void remember(String user, String secret) { }
            @Override public void forget(String user) { }
            @Override public boolean knows(String user) { return true; }
            @Override public Set<String> knownUsers() { return Set.of(); }
        }, "http://127.0.0.1:" + port + "/jmap/", 5, 20);

        JmapClient.Blob blob = client.upload("priya@jarurat.care", "application/pdf", bytes.length,
                () -> new ByteArrayInputStream(bytes));

        assertThat(blob.blobId()).isEqualTo("Gblob-9");
        assertThat(uploadHeaders).hasSize(1);
        assertThat(uploadHeaders.get(0)).contains("application/pdf").contains("len=11").contains("enc=null");
        assertThat(uploadSizes).containsExactly(bytes.length);
    }

    /** A part with no blob behind it is a message that would arrive with a missing file. */
    @Test
    @DisplayName("an attachment with no blob is refused before the request is built")
    void aBlobLessPartIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                mail.send("priya@jarurat.care", List.of("asha@jarurat.care"), List.of(),
                        "x", null, "y",
                        List.of(new Attachment(null, "  ", "ghost.pdf", "application/pdf", 1, "attachment", null))))
                .isInstanceOf(MailException.class)
                .hasMessageContaining("no uploaded blob");
    }

    // ------------------------------------------------------------------ helpers

    /** The Email object out of the last Email/set create the client sent. */
    private JsonNode draftFromLastSet() {
        List<JsonNode> sets = new ArrayList<>();
        for (String call : apiCalls) {
            JsonNode root = json.readTree(call);
            for (JsonNode invocation : root.path("methodCalls")) {
                if ("Email/set".equals(invocation.path(0).asString())) sets.add(invocation.path(1));
            }
        }
        assertThat(sets).isNotEmpty();
        return sets.get(sets.size() - 1).path("create").path("draft");
    }

    /**
     * Enough of a JMAP server to get one send through: the folders it asks for, the
     * identity it sends as, and a set that always succeeds.
     */
    private String replyFor(String request) {
        JsonNode root = json.readTree(request);
        StringBuilder out = new StringBuilder("{\"methodResponses\":[");
        boolean first = true;
        for (JsonNode invocation : root.path("methodCalls")) {
            String name = invocation.path(0).asString();
            String callId = invocation.path(2).asString();
            String args = switch (name) {
                case "Mailbox/get" -> "{\"list\":["
                        + "{\"id\":\"mb-drafts\",\"name\":\"Drafts\",\"role\":\"drafts\"},"
                        + "{\"id\":\"mb-sent\",\"name\":\"Sent\",\"role\":\"sent\"}]}";
                case "Identity/get" -> "{\"list\":[{\"id\":\"id-1\",\"name\":\"Priya\","
                        + "\"email\":\"priya@jarurat.care\"}]}";
                case "Email/set" -> "{\"created\":{\"draft\":{\"id\":\"em-1\"}}}";
                case "EmailSubmission/set" -> "{\"created\":{\"sub\":{\"id\":\"sub-1\"}}}";
                default -> "{}";
            };
            if (!first) out.append(',');
            first = false;
            out.append("[\"").append(name).append("\",").append(args).append(",\"").append(callId).append("\"]");
        }
        return out.append("]}").toString();
    }

    private static void answer(HttpExchange ex, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (InputStream ignored = ex.getRequestBody()) {
            ignored.readAllBytes();
        }
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
