package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.Identity;
import com.jarurat.mailer.mail.MailAddress;
import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.mail.MailFolder;
import com.jarurat.mailer.mail.MailService;
import com.jarurat.mailer.mail.MessagePage;
import com.jarurat.mailer.mail.MessageSummary;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recipient autocomplete, both halves: the ranking and caching in
 * ContactSuggestService, and the shape ContactSuggestApi promises to the client.
 *
 * The interesting assertions here are the ones about failing rather than the ones
 * about working. This thing fires on every keystroke into a compose box, so the
 * ways it is allowed to go wrong are narrow: it may return nothing, and that is
 * all. It may not throw, it may not turn a mail server outage into a status the
 * screen has to interpret, it may not make a person wait on a round trip while
 * they are typing, and it may not grow a cache without a bound. Each of those is
 * asserted below, because each of them is silent when it breaks: an autocomplete
 * that quietly blocks for two seconds looks exactly like one that is thinking.
 */
class ContactSuggestTest {

    private static final String MAILBOX = "hr@jarurat.care";
    private static final String OTHER_MAILBOX = "support@jarurat.care";
    private static final String SENT_ID = "folder-sent";
    private static final String INBOX_ID = "folder-inbox";

    private final MailService mail = mock(MailService.class);

    /**
     * A generous deadline everywhere except the one test that is about the deadline.
     * The mock answers instantly, so the harvest always finishes inside it and every
     * other test can assert on a populated list without sleeping or polling.
     */
    private ContactSuggestService service() {
        return new ContactSuggestService(mail, 60, 2000, 200, 32);
    }

    // ------------------------------------------------------------------ ranking

    @Test
    @DisplayName("somebody this mailbox writes to outranks somebody it merely hears from")
    void sentOutranksInbox() {
        Instant today = Instant.now();
        givenFolders();
        givenSent(message(today, to("wrote.to@example.org", "Wrote To")));
        givenInbox(message(today, from("heard.from@example.org", "Heard From")));

        List<ContactSuggestService.Contact> found = service().suggest(MAILBOX, "", 10);

        assertThat(found).extracting(ContactSuggestService.Contact::email)
                .containsExactly("wrote.to@example.org", "heard.from@example.org");
    }

    @Test
    @DisplayName("a regular correspondent beats a one-off, and a recent one beats an old one")
    void recencyAndFrequencyBothCount() {
        Instant now = Instant.now();
        givenFolders();
        givenSent(
                // Written to three times, but all of it a year ago.
                message(now.minus(360, ChronoUnit.DAYS), to("regular@example.org", "Regular")),
                message(now.minus(350, ChronoUnit.DAYS), to("regular@example.org", "Regular")),
                message(now.minus(340, ChronoUnit.DAYS), to("regular@example.org", "Regular")),
                // Written to once, yesterday.
                message(now.minus(1, ChronoUnit.DAYS), to("recent@example.org", "Recent")),
                // Written to once, a year ago. Loses to both.
                message(now.minus(355, ChronoUnit.DAYS), to("stale@example.org", "Stale")));
        givenNoInbox();

        List<ContactSuggestService.Contact> found = service().suggest(MAILBOX, "", 10);

        assertThat(found).extracting(ContactSuggestService.Contact::email)
                .containsExactly("recent@example.org", "regular@example.org", "stale@example.org");
    }

    @Test
    @DisplayName("the newest spelling of a name wins, and lastSeen is the newest sighting")
    void theMostRecentNameAndDateSurvive() {
        Instant now = Instant.now();
        Instant older = now.minus(40, ChronoUnit.DAYS);
        givenFolders();
        givenSent(
                message(older, to("p@example.org", "Priya Sharma")),
                message(now, to("p@example.org", "Priya Nair")));
        givenNoInbox();

        ContactSuggestService.Contact found = service().suggest(MAILBOX, "p@", 10).get(0);

        assertThat(found.name()).isEqualTo("Priya Nair");
        assertThat(found.lastSeen()).isEqualTo(now);
    }

    // ------------------------------------------------------------------ matching

    @Test
    @DisplayName("a prefix matches the address, the part before the @, and each word of the name")
    void prefixMatchesEveryHandleAPersonMightType() {
        givenFolders();
        givenSent(message(Instant.now(), to("priya.sharma@example.org", "Priya Sharma")));
        givenNoInbox();
        ContactSuggestService contacts = service();

        assertThat(emails(contacts.suggest(MAILBOX, "priya.s", 10))).containsExactly("priya.sharma@example.org");
        assertThat(emails(contacts.suggest(MAILBOX, "priya.sharma@ex", 10))).containsExactly("priya.sharma@example.org");
        assertThat(emails(contacts.suggest(MAILBOX, "Priya S", 10))).containsExactly("priya.sharma@example.org");
        assertThat(emails(contacts.suggest(MAILBOX, "sharma", 10))).containsExactly("priya.sharma@example.org");
        assertThat(emails(contacts.suggest(MAILBOX, "@exam", 10))).containsExactly("priya.sharma@example.org");
        // Prefix only. A substring match would put an unrelated address under the
        // cursor of somebody who is halfway through typing a different one.
        assertThat(contacts.suggest(MAILBOX, "harma", 10)).isEmpty();
    }

    @Test
    @DisplayName("case and accents are ignored on both sides of the comparison")
    void matchingFoldsCaseAndAccents() {
        givenFolders();
        givenSent(message(Instant.now(), to("jose@example.org", "José Ramírez")));
        givenNoInbox();
        ContactSuggestService contacts = service();

        assertThat(emails(contacts.suggest(MAILBOX, "jose", 10))).containsExactly("jose@example.org");
        assertThat(emails(contacts.suggest(MAILBOX, "JOSÉ", 10))).containsExactly("jose@example.org");
        assertThat(emails(contacts.suggest(MAILBOX, "ramirez", 10))).containsExactly("jose@example.org");
        assertThat(emails(contacts.suggest(MAILBOX, "Ramí", 10))).containsExactly("jose@example.org");
    }

    // ------------------------------------------------------------------ what is left out

    @Test
    @DisplayName("the open mailbox is never suggested back to itself")
    void selfIsNotAContact() {
        givenFolders();
        givenSent(message(Instant.now(), to(MAILBOX, "Us"), to("real@example.org", "Real")));
        givenNoInbox();
        when(mail.listIdentities(MAILBOX)).thenReturn(List.of(
                new Identity("i1", "Jarurat HR", MAILBOX, null, null, null)));

        assertThat(emails(service().suggest(MAILBOX, "", 10))).containsExactly("real@example.org");
    }

    @Test
    @DisplayName("an automated sender is dropped from the Inbox side but kept if you wrote to it")
    void automatedSendersAreFilteredOnlyOnTheWayIn() {
        Instant now = Instant.now();
        givenFolders();
        givenSent(message(now, to("noreply@partner.org", "Partner Desk")));
        givenInbox(
                message(now, from("no-reply@newsletter.org", "Weekly")),
                message(now, from("mailer-daemon@example.org", "Daemon")),
                message(now, from("person@example.org", "Person")));

        assertThat(emails(service().suggest(MAILBOX, "", 10)))
                .containsExactly("noreply@partner.org", "person@example.org");
    }

    @Test
    @DisplayName("an address the mail server would refuse at send time is not offered at compose time")
    void malformedAddressesAreDropped() {
        givenFolders();
        givenSent(message(Instant.now(),
                to("not an address", "Broken"),
                to("also@bad", "Broken Too"),
                to("fine@example.org", "Fine")));
        givenNoInbox();

        assertThat(emails(service().suggest(MAILBOX, "", 10))).containsExactly("fine@example.org");
    }

    @Test
    @DisplayName("an organisation address with no correspondence behind it is offered, but last")
    void theDirectoryIsAFloorAndNotARank() {
        givenFolders();
        givenSent(message(Instant.now().minus(500, ChronoUnit.DAYS), to("ancient@example.org", "Ancient")));
        givenNoInbox();
        when(mail.listIdentities(MAILBOX)).thenReturn(List.of(
                new Identity("i2", "Jarurat Support", OTHER_MAILBOX, null, null, null)));

        List<ContactSuggestService.Contact> found = service().suggest(MAILBOX, "", 10);

        assertThat(emails(found)).containsExactly("ancient@example.org", OTHER_MAILBOX);
        // No correspondence means no date, and the contract says that is "" not null.
        assertThat(found.get(1).lastSeen()).isNull();
    }

    // ------------------------------------------------------------------ cost and failure

    @Test
    @DisplayName("a warm cache costs no round trip, however many keystrokes arrive")
    void keystrokesAfterTheFirstAreFree() {
        givenFolders();
        givenSent(message(Instant.now(), to("priya@example.org", "Priya")));
        givenNoInbox();
        ContactSuggestService contacts = service();

        for (String typed : List.of("p", "pr", "pri", "priy", "priya")) {
            assertThat(contacts.suggest(MAILBOX, typed, 10)).hasSize(1);
        }

        // One harvest for five keystrokes. Without the cache this is five folder
        // listings and ten message queries against Stalwart for one typed word.
        verify(mail, times(1)).listFolders(MAILBOX);
        verify(mail, times(1)).listMessages(eq(MAILBOX), eq(SENT_ID), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a mail server that is refusing to talk is an empty list, not an exception")
    void everyFailureDegradesToNothing() {
        when(mail.listFolders(anyString()))
                .thenThrow(new MailException(MailException.Kind.TRANSPORT, "Cannot reach the mail server"));
        when(mail.listIdentities(anyString()))
                .thenThrow(new MailException(MailException.Kind.AUTH, "No mail credential held"));

        assertThat(service().suggest(MAILBOX, "pri", 10)).isEmpty();
    }

    @Test
    @DisplayName("one folder failing still leaves the other folder's contacts")
    void aPartialHarvestIsStillWorthKeeping() {
        givenFolders();
        when(mail.listMessages(eq(MAILBOX), eq(SENT_ID), anyInt(), anyInt()))
                .thenThrow(new MailException(MailException.Kind.METHOD, "Email/query failed"));
        givenInbox(message(Instant.now(), from("survivor@example.org", "Survivor")));

        assertThat(emails(service().suggest(MAILBOX, "", 10))).containsExactly("survivor@example.org");
    }

    @Test
    @DisplayName("a slow mail server is abandoned at the deadline instead of holding the compose box")
    void theDeadlineIsRealAndMeasured() throws Exception {
        // Three seconds is well past anything a person will tolerate mid-word, and it
        // is the shape of a real failure: Stalwart reachable but not answering, with
        // JmapClient's twenty second request timeout still to run.
        when(mail.listFolders(anyString())).thenAnswer(invocation -> {
            Thread.sleep(3000);
            return List.of();
        });
        ContactSuggestService contacts = new ContactSuggestService(mail, 60, 120, 200, 32);

        long startedAt = System.nanoTime();
        List<ContactSuggestService.Contact> found = contacts.suggest(MAILBOX, "pri", 10);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(found).isEmpty();
        // The assertion is deliberately loose at the top end, because this runs on a
        // build machine and not a bench. What it has to prove is that the call came
        // back on the order of the deadline rather than on the order of the harvest.
        assertThat(elapsedMs).isLessThan(1500L);
    }

    @Test
    @DisplayName("the cache cannot grow past its bound")
    void theCacheIsBounded() {
        givenFolders();
        givenSent(message(Instant.now(), to("someone@example.org", "Someone")));
        givenNoInbox();
        ContactSuggestService contacts = new ContactSuggestService(mail, 60, 2000, 200, 2);

        for (int i = 0; i < 8; i++) {
            assertThat(contacts.suggest("mailbox" + i + "@jarurat.care", "", 10)).isNotEmpty();
        }

        assertThat(contacts.cachedMailboxes()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("one mailbox never sees another mailbox's contacts")
    void mailboxesAreCachedApart() {
        givenFolders();
        when(mail.listMessages(eq(MAILBOX), eq(SENT_ID), anyInt(), anyInt()))
                .thenReturn(page(message(Instant.now(), to("hr.contact@example.org", "HR Contact"))));
        when(mail.listMessages(eq(OTHER_MAILBOX), eq(SENT_ID), anyInt(), anyInt()))
                .thenReturn(page(message(Instant.now(), to("support.contact@example.org", "Support Contact"))));
        givenNoInbox();
        ContactSuggestService contacts = service();

        assertThat(emails(contacts.suggest(MAILBOX, "", 10))).containsExactly("hr.contact@example.org");
        assertThat(emails(contacts.suggest(OTHER_MAILBOX, "", 10))).containsExactly("support.contact@example.org");
    }

    // ------------------------------------------------------------------ the contract on the wire

    @Test
    @DisplayName("the JSON is exactly the shape the contract promises")
    void theResponseShapeHolds() {
        Instant when = Instant.parse("2026-08-27T09:14:02Z");
        givenFolders();
        givenSent(message(when, to("priya@example.org", "Priya Sharma")));
        givenNoInbox();
        when(mail.listIdentities(MAILBOX)).thenReturn(List.of(
                new Identity("i3", null, OTHER_MAILBOX, null, null, null)));

        Map<String, Object> body = api(service(), MAILBOX).contacts(null, session(), "  pri  ", "");

        assertThat(body.get("q")).isEqualTo("pri");
        assertThat(body.get("locked")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("contacts");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly(
                Map.entry("email", "priya@example.org"),
                Map.entry("name", "Priya Sharma"),
                Map.entry("lastSeen", "2026-08-27T09:14:02Z"));
    }

    @Test
    @DisplayName("an address with no name and no date answers with empty strings, never nulls")
    void absentFieldsAreEmptyStringsAndNotNulls() {
        givenFolders();
        givenNoSent();
        givenNoInbox();
        when(mail.listIdentities(MAILBOX)).thenReturn(List.of(
                new Identity("i4", null, OTHER_MAILBOX, null, null, null)));

        Map<String, Object> body = api(service(), MAILBOX).contacts(null, session(), "", "");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("contacts");
        assertThat(rows.get(0).get("name")).isEqualTo("");
        assertThat(rows.get(0).get("lastSeen")).isEqualTo("");
    }

    @Test
    @DisplayName("a locked mailbox is an empty list with a flag, not the 409 the other routes give")
    void aLockedMailboxDoesNotInterruptTyping() {
        MailboxAccess locked = mock(MailboxAccess.class);
        when(locked.current(any(), any())).thenReturn(null);

        Map<String, Object> body = new ContactSuggestApi(service(), locked)
                .contacts(null, session(), "pri", "");

        assertThat(body.get("locked")).isEqualTo(true);
        assertThat(body.get("contacts")).isEqualTo(List.of());
        // require() would have thrown MailboxLockedException, which the screen turns
        // into an unlock sheet. Mid-word is the wrong moment for that.
        verify(locked, times(0)).require(any(), any());
    }

    @Test
    @DisplayName("limit is clamped, and a junk limit falls back rather than failing the request")
    void limitIsClampedAndNeverFatal() {
        ContactSuggestService contacts = mock(ContactSuggestService.class);
        when(contacts.suggest(anyString(), anyString(), anyInt())).thenReturn(List.of());
        ContactSuggestApi endpoint = api(contacts, MAILBOX);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);

        endpoint.contacts(null, session(), "a", "");
        endpoint.contacts(null, session(), "a", "3");
        endpoint.contacts(null, session(), "a", "9999");
        endpoint.contacts(null, session(), "a", "0");
        endpoint.contacts(null, session(), "a", "abc");
        endpoint.contacts(null, session(), "a", "-4");

        verify(contacts, times(6)).suggest(anyString(), anyString(), limits.capture());
        assertThat(limits.getAllValues()).containsExactly(8, 3, 25, 1, 8, 1);
    }

    // ------------------------------------------------------------------ fixtures

    private ContactSuggestApi api(ContactSuggestService contacts, String open) {
        MailboxAccess access = mock(MailboxAccess.class);
        when(access.current(any(), any())).thenReturn(open);
        return new ContactSuggestApi(contacts, access);
    }

    private static HttpSession session() {
        return new MockHttpSession();
    }

    private void givenFolders() {
        when(mail.listFolders(anyString())).thenReturn(List.of(
                new MailFolder(INBOX_ID, "Inbox", "inbox", null, 0, 0, 0),
                new MailFolder(SENT_ID, "Sent", "sent", null, 0, 0, 0)));
    }

    private void givenSent(MessageSummary... rows) {
        when(mail.listMessages(anyString(), eq(SENT_ID), anyInt(), anyInt())).thenReturn(page(rows));
    }

    private void givenInbox(MessageSummary... rows) {
        when(mail.listMessages(anyString(), eq(INBOX_ID), anyInt(), anyInt())).thenReturn(page(rows));
    }

    private void givenNoSent() {
        when(mail.listMessages(anyString(), eq(SENT_ID), anyInt(), anyInt())).thenReturn(page());
    }

    private void givenNoInbox() {
        when(mail.listMessages(anyString(), eq(INBOX_ID), anyInt(), anyInt())).thenReturn(page());
    }

    /** total equals what is in the page, so MessagePage.hasMore is false and paging stops. */
    private static MessagePage page(MessageSummary... rows) {
        return new MessagePage(List.of(rows), 0, 100, rows.length);
    }

    /** A Sent row: the addresses given are its recipients. */
    private static MessageSummary message(Instant receivedAt, MailAddress... counterparties) {
        return new MessageSummary("id-" + receivedAt.toEpochMilli() + "-" + counterparties.length,
                "thread", "Subject",
                List.of(counterparties), List.of(counterparties), List.of(),
                "preview", receivedAt, 1024L,
                true, false, false, false, false, List.of());
    }

    private static MailAddress to(String email, String name) {
        return new MailAddress(name, email);
    }

    private static MailAddress from(String email, String name) {
        return new MailAddress(name, email);
    }

    private static List<String> emails(List<ContactSuggestService.Contact> found) {
        return found.stream().map(ContactSuggestService.Contact::email).toList();
    }
}
