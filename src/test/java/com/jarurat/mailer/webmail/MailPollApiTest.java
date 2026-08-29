package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.InMemoryMailCredentialStore;
import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.mail.MailFolder;
import com.jarurat.mailer.mail.MailService;
import com.jarurat.mailer.mail.MailSession;
import com.jarurat.mailer.messagelog.MessageLogService;
import com.jarurat.mailer.security.LoginRateLimiter;
import com.jarurat.mailer.services.AuditService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GET /api/mail/poll is the only endpoint in this application a browser is allowed
 * to call on a timer, so the thing that has to be pinned down is not that it
 * answers but what it costs to answer, forty-five seconds at a time, per open tab,
 * all day. Two facts carry that: how many round trips a poll makes to Stalwart, and
 * how much work it asks for inside them.
 *
 * The JmapClient here is a spy over a real instance rather than a plain mock. The
 * request builders (accountArgs, invocation, newArray) and the reply reader
 * (response) are the real ones, so what these tests assert on is the JSON the
 * controller genuinely puts on the wire, not a restatement of the controller's own
 * source. Only session() and call() are stubbed, which is exactly the socket.
 */
class MailPollApiTest {

    private static final String USER = "priya@jarurat.care";
    private static final String INBOX = "mb-inbox-1";

    private final ObjectMapper json = new ObjectMapper();

    private final MailboxAccess mailbox = mock(MailboxAccess.class);
    private final MailService mail = mock(MailService.class);
    private final JmapClient jmap = spy(new JmapClient(
            new InMemoryMailCredentialStore(), "https://127.0.0.1/jmap/", 1, 1));

    /** Every methodCalls array this controller handed to the transport, in order. */
    private final List<ArrayNode> sent = new ArrayList<>();

    /** What the next call() answers with. Swapped by the tests that need a second answer. */
    private final AtomicReference<String> reply = new AtomicReference<>(inboxReply(INBOX, 3, 812, NEWEST));

    private MailPollApi controller;
    private HttpSession session;

    private static final String NEWEST = """
            {"id":"e-99","receivedAt":"2026-08-29T09:14:02Z","subject":"Camp list for Thane",
             "from":[{"name":"Sunita Rao","email":"sunita@example.org"}],"keywords":{}}""";

    MailPollApiTest() {
        doReturn(new MailSession(USER, "g", URI.create("https://localhost/jmap/"), "", "", "s1"))
                .when(jmap).session(anyString());
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(2));
            return json.readTree(reply.get()).path("methodResponses");
        }).when(jmap).call(anyString(), anyList(), any(ArrayNode.class));

        session = new MockHttpSession();
        when(mailbox.require(any(), any())).thenReturn(USER);
        when(mail.folderByRole(USER, "inbox"))
                .thenReturn(new MailFolder(INBOX, "Inbox", "inbox", null, 0, 812, 3));
        controller = new MailPollApi(mailbox, mail, jmap);
    }

    // ------------------------------------------------------------------ cost

    @Test
    @DisplayName("a poll is one round trip to the mail server, and the first one is two")
    void thePollCostsOneRoundTrip() {
        controller.poll(null, session);
        // The cold path: folderByRole learns the inbox id, which is its own request.
        assertThat(sent).as("the steady-state request").hasSize(1);
        verify(mail, times(1)).folderByRole(USER, "inbox");

        for (int i = 0; i < 20; i++) controller.poll(null, session);

        assertThat(sent).as("one JMAP request per poll and no more").hasSize(21);
        // Twenty polls after the first must never ask for the inbox id again. This is
        // the whole reason the id is cached: without it every poll is two round trips
        // instead of one, which doubles the standing cost of leaving a tab open.
        verify(mail, times(1)).folderByRole(USER, "inbox");
    }

    @Test
    @DisplayName("the three method calls ride in one request, and the query is capped at one message")
    void theRequestIsBounded() {
        controller.poll(null, session);

        ArrayNode calls = sent.get(0);
        assertThat(methodNames(calls)).containsExactly("Mailbox/get", "Email/query", "Email/get");

        JsonNode boxes = calls.get(0).get(1);
        assertThat(boxes.path("ids").isNull()).as("every folder, so the inbox counter is in the answer").isTrue();
        assertThat(propertyList(boxes)).containsExactly("id", "role", "totalEmails", "unreadEmails");

        JsonNode query = calls.get(1).get(1);
        assertThat(query.path("filter").path("inMailbox").asString()).isEqualTo(INBOX);
        assertThat(query.path("limit").asInt()).as("one message, never a page").isEqualTo(1);
        assertThat(query.path("position").asInt()).isZero();
        // calculateTotal is the expensive flag on a JMAP query and the unread count
        // already comes off the mailbox counter, so asking for it would be paying
        // twice for one number.
        assertThat(query.path("calculateTotal").asBoolean(true)).isFalse();
        JsonNode sort = query.path("sort").path(0);
        assertThat(sort.path("property").asString()).isEqualTo("receivedAt");
        assertThat(sort.path("isAscending").asBoolean(true)).isFalse();

        JsonNode get = calls.get(2).get(1);
        // A back reference, not a second round trip. If this ever became a literal
        // ids array the controller would have to wait for the query first.
        assertThat(get.path("#ids").path("resultOf").asString()).isEqualTo("q0");
        assertThat(get.path("#ids").path("name").asString()).isEqualTo("Email/query");
        assertThat(propertyList(get)).doesNotContain("textBody", "htmlBody", "bodyValues", "attachments");
        assertThat(get.path("fetchTextBodyValues").isMissingNode()).as("no body is ever fetched").isTrue();
        assertThat(get.path("fetchHTMLBodyValues").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("a poll answer is smaller on the wire than the folder listing it must not exceed")
    void theAnswerIsSmallerThanAFolderListing() {
        Map<String, Object> poll = controller.poll(null, session);

        int pollBytes = json.writeValueAsString(poll).getBytes(StandardCharsets.UTF_8).length;
        int foldersBytes = json.writeValueAsString(foldersAnswer()).getBytes(StandardCharsets.UTF_8).length;

        System.out.println("[poll] answer bytes=" + pollBytes
                + "  /api/mail/folders answer bytes=" + foldersBytes
                + "  per hour at 45s=" + (pollBytes * 80) + " bytes");
        assertThat(pollBytes).isLessThan(foldersBytes);
    }

    // ------------------------------------------------------------------ answer

    @Test
    @DisplayName("the unread count is the inbox counter and the newest message comes back whole")
    void theAnswerCarriesWhatANotificationNeeds() {
        Map<String, Object> out = controller.poll(null, session);

        assertThat(out.get("mailbox")).isEqualTo(USER);
        assertThat(out.get("unread")).isEqualTo(3);
        assertThat(out.get("total")).isEqualTo(812);

        @SuppressWarnings("unchecked")
        Map<String, Object> newest = (Map<String, Object>) out.get("newest");
        assertThat(newest.get("id")).isEqualTo("e-99");
        assertThat(newest.get("receivedAt")).isEqualTo("2026-08-29T09:14:02Z");
        assertThat(newest.get("subject")).isEqualTo("Camp list for Thane");
        assertThat(newest.get("from")).isEqualTo("Sunita Rao");
        assertThat(newest.get("seen")).isEqualTo(false);
    }

    @Test
    @DisplayName("newest is the newest message, read or not, so reading it is not an arrival")
    void readingTheTopMessageIsNotAnArrival() {
        Map<String, Object> before = controller.poll(null, session);

        // The same message, now marked read. Had this endpoint answered with the
        // newest UNREAD message instead, the id would change here and a client
        // watching it would announce the message underneath as new mail.
        reply.set(inboxReply(INBOX, 2, 812, """
                {"id":"e-99","receivedAt":"2026-08-29T09:14:02Z","subject":"Camp list for Thane",
                 "from":[{"name":"Sunita Rao","email":"sunita@example.org"}],"keywords":{"$seen":true}}"""));
        Map<String, Object> after = controller.poll(null, session);

        assertThat(newestOf(after).get("id")).isEqualTo(newestOf(before).get("id"));
        assertThat(newestOf(after).get("seen")).isEqualTo(true);
        assertThat(after.get("unread")).isEqualTo(2);
    }

    @Test
    @DisplayName("a sender with no display name falls back to the address")
    void anUnnamedSenderReadsAsItsAddress() {
        reply.set(inboxReply(INBOX, 1, 1, """
                {"id":"e-1","receivedAt":"2026-08-29T10:00:00Z","subject":"",
                 "from":[{"email":"noreply@vendor.example"}],"keywords":{}}"""));

        assertThat(newestOf(controller.poll(null, session)).get("from")).isEqualTo("noreply@vendor.example");
    }

    @Test
    @DisplayName("a long subject is clipped rather than shipped in full every 45 seconds")
    void theSubjectIsClipped() {
        String huge = "x".repeat(4000);
        reply.set(inboxReply(INBOX, 1, 1, """
                {"id":"e-2","receivedAt":"2026-08-29T10:00:00Z","subject":"%s",
                 "from":[{"email":"a@b.example"}],"keywords":{}}""".formatted(huge)));

        String subject = (String) newestOf(controller.poll(null, session)).get("subject");
        assertThat(subject).hasSize(140).endsWith("...");
    }

    @Test
    @DisplayName("an empty inbox answers zero and no newest message rather than failing")
    void anEmptyInboxIsNotAFailure() {
        reply.set("""
                {"methodResponses":[
                  ["Mailbox/get",{"list":[{"id":"%s","role":"inbox","totalEmails":0,"unreadEmails":0}]},"p0"],
                  ["Email/query",{"ids":[]},"q0"],
                  ["Email/get",{"list":[]},"g0"]]}""".formatted(INBOX));

        Map<String, Object> out = controller.poll(null, session);
        assertThat(out.get("unread")).isEqualTo(0);
        assertThat(out.get("newest")).isNull();
    }

    @Test
    @DisplayName("an account with no inbox answers zero instead of stopping the client's timer")
    void anAccountWithNoInboxIsNotAFailure() {
        when(mail.folderByRole(USER, "inbox")).thenReturn(null);

        Map<String, Object> out = controller.poll(null, session);
        assertThat(out.get("unread")).isEqualTo(0);
        assertThat(out.get("newest")).isNull();
        assertThat(sent).as("nothing was asked of the mail server").isEmpty();
    }

    // ------------------------------------------------------------------ the cache going stale

    @Test
    @DisplayName("an inbox id that has moved is corrected from the same answer, once, and not in a loop")
    void aStaleInboxIdCorrectsItself() {
        controller.poll(null, session);
        sent.clear();

        // The account was rebuilt: the id the cache holds is gone and Mailbox/get in
        // the very same request says so. The first attempt asked about the wrong
        // folder, so its Email/query answer is discarded rather than reported.
        String movedId = "mb-inbox-2";
        when(mail.folderByRole(USER, "inbox"))
                .thenReturn(new MailFolder(movedId, "Inbox", "inbox", null, 0, 5, 5));
        reply.set(inboxReply(movedId, 5, 5, NEWEST));

        Map<String, Object> out = controller.poll(null, session);

        assertThat(out.get("folderId")).isEqualTo(movedId);
        assertThat(out.get("unread")).isEqualTo(5);
        assertThat(sent).as("one wasted attempt and one good one, never more").hasSize(2);
        assertThat(sent.get(1).get(1).get(1).path("filter").path("inMailbox").asString()).isEqualTo(movedId);

        // And the correction sticks: the next poll is back to one request.
        sent.clear();
        controller.poll(null, session);
        assertThat(sent).hasSize(1);
    }

    @Test
    @DisplayName("a method error that a fresh inbox id cannot fix is reported, not retried forever")
    void aHardFailureIsNotRetriedForever() {
        controller.poll(null, session);
        sent.clear();
        reply.set("""
                {"methodResponses":[["error",{"type":"serverFail"},"p0"]]}""");

        assertThatThrownBy(() -> controller.poll(null, session)).isInstanceOf(MailException.class);
        assertThat(sent).as("one attempt, because the inbox id had not moved").hasSize(1);
    }

    // ------------------------------------------------------------------ stopping the client

    @Test
    @DisplayName("a locked mailbox is 409 with locked:true, which is the client's stop signal")
    void aLockedMailboxAnswers409() {
        ResponseEntity<?> answer = controller.onLocked(
                new MailboxAccess.MailboxLockedException("Open your mailbox to read mail on this device."));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(answer.getBody()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) answer.getBody()).get("locked")).isEqualTo(true);
    }

    @Test
    @DisplayName("a refused mailbox password reads as locked too, not as a mail server outage")
    void aRefusedPasswordAnswers409() {
        ResponseEntity<?> answer = controller.onMailFailure(
                new MailException(MailException.Kind.AUTH, "That mailbox password was not accepted."));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((Map<?, ?>) answer.getBody()).get("locked")).isEqualTo(true);
    }

    @Test
    @DisplayName("an unreachable mail server is 502, which the client backs off from rather than stops on")
    void anOutageAnswers502() {
        ResponseEntity<?> answer = controller.onMailFailure(
                new MailException(MailException.Kind.TRANSPORT, "connect timed out"));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(((Map<?, ?>) answer.getBody()).get("locked")).isNull();
    }

    @Test
    @DisplayName("a caller without MAIL_READ is refused by the security chain, not softened to a 502")
    void anAuthorisationDenialIsNotSwallowed() {
        assertThatThrownBy(() -> controller.onFailure(
                new org.springframework.security.access.AccessDeniedException("no")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ helpers

    /** The real /api/mail/folders answer for the same mailbox, for the size comparison. */
    private Map<String, Object> foldersAnswer() {
        MailService folderService = mock(MailService.class);
        MailboxAccess access = mock(MailboxAccess.class);
        when(access.require(any(), any())).thenReturn(USER);
        when(folderService.listFolders(eq(USER))).thenReturn(List.of(
                new MailFolder(INBOX, "Inbox", "inbox", null, 0, 812, 3),
                new MailFolder("mb-2", "Drafts", "drafts", null, 0, 4, 0),
                new MailFolder("mb-3", "Sent", "sent", null, 0, 611, 0),
                new MailFolder("mb-4", "Archive", "archive", null, 0, 2044, 0),
                new MailFolder("mb-5", "Junk", "junk", null, 0, 87, 12),
                new MailFolder("mb-6", "Trash", "trash", null, 0, 31, 0)));

        MailApiController folders = new MailApiController(folderService, access,
                mock(AuditService.class), mock(MessageLogService.class), jmap, new LoginRateLimiter());
        return folders.folders(null, session);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> newestOf(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get("newest");
    }

    private static List<String> methodNames(ArrayNode calls) {
        List<String> names = new ArrayList<>();
        for (JsonNode call : calls) names.add(call.get(0).asString());
        return names;
    }

    private static List<String> propertyList(JsonNode args) {
        List<String> props = new ArrayList<>();
        for (JsonNode p : args.path("properties")) props.add(p.asString());
        return props;
    }

    /** One canned JMAP reply carrying all three responses, the way Stalwart sends them. */
    private static String inboxReply(String inboxId, int unread, int total, String newest) {
        return """
                {"methodResponses":[
                  ["Mailbox/get",{"list":[
                     {"id":"%s","role":"inbox","totalEmails":%d,"unreadEmails":%d},
                     {"id":"mb-9","role":"sent","totalEmails":611,"unreadEmails":0}]},"p0"],
                  ["Email/query",{"ids":["e-99"]},"q0"],
                  ["Email/get",{"list":[%s]},"g0"]]}"""
                .formatted(inboxId, total, unread, newest);
    }
}
