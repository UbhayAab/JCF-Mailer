package com.jarurat.mailer.mail;

import com.jarurat.mailer.services.SesSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The JSON a send actually produces, asserted property by property.
 *
 * These are shape tests and not behaviour tests, and that is the point. A blind copy
 * that leaks does not throw, does not fail a status code and does not show up in any
 * log we keep; the first anybody hears of it is a recipient reading a list of names
 * they were not supposed to see, which by then has already happened. The only place
 * to catch it is in the request before it is sent, so these walk the finished
 * methodCalls array and assert on what is and is not in it.
 */
class MailSendShapeTest {

    private static final String MAILBOX = "priya@jarurat.care";

    private final FakeJmap jmap = new FakeJmap();
    private final SesSender ses = mock(SesSender.class);
    private final MailService mail = new MailService(jmap.client, ses, 100, 1_000_000, 17_825_792L);

    MailSendShapeTest() {
        when(ses.toPlainText(anyString())).thenReturn("Please view this message in an HTML capable email client.");
    }

    // ------------------------------------------------------------------ blind copies

    /**
     * The whole promise of a blind copy, stated as an assertion rather than as a
     * comment: no property of the Email object anywhere in the tree is named bcc, so
     * there is no Bcc header for the mail server to render and nothing any recipient's
     * client could display. The search is over the serialised request rather than over
     * the one property we happen to remember, because a future edit that adds the
     * header somewhere else is exactly the failure this test exists to catch.
     */
    @Test
    @DisplayName("a blind copy never reaches the message, only the SMTP envelope")
    void blindCopyStaysOutOfTheMessage() {
        mail.send(MAILBOX, Outgoing.message(
                        List.of("hospital@tmc.gov.in"), List.of("finance@jarurat.care"),
                        "Camp dates", null, "Confirming the dates.")
                .withBcc(List.of("trustee@jarurat.care", "auditor@example.org")));

        ObjectNode created = jmap.argsFor("Email/set", "c0");
        JsonNode email = created.path("create").path("draft");

        assertThat(namesIn(email)).doesNotContain("bcc");
        assertThat(email.toString().toLowerCase()).doesNotContain("trustee@jarurat.care");
        assertThat(email.toString().toLowerCase()).doesNotContain("auditor@example.org");

        // And they are in the envelope exactly once each, because a recipient named
        // twice is either delivered twice or bounced on the second copy.
        assertThat(envelopeRecipients()).containsExactly(
                "hospital@tmc.gov.in", "finance@jarurat.care",
                "trustee@jarurat.care", "auditor@example.org");
    }

    @Test
    @DisplayName("an address in both To and Bcc is delivered once and stays visible")
    void anAddressInTwoFieldsIsSentOnce() {
        mail.send(MAILBOX, Outgoing.message(
                        List.of("priya@jarurat.care"), List.of(), "Note", null, "Text")
                .withBcc(List.of("PRIYA@jarurat.care", "trustee@jarurat.care")));

        assertThat(envelopeRecipients()).containsExactly("priya@jarurat.care", "trustee@jarurat.care");
    }

    @Test
    @DisplayName("a message addressed only to blind copies still has an envelope and no empty To")
    void blindOnlySendHasNoEmptyToHeader() {
        mail.send(MAILBOX, Outgoing.message(List.of(), List.of(), "Notice", null, "Text")
                .withBcc(List.of("one@example.org", "two@example.org")));

        JsonNode email = jmap.argsFor("Email/set", "c0").path("create").path("draft");
        assertThat(email.has("to")).isFalse();
        assertThat(envelopeRecipients()).containsExactly("one@example.org", "two@example.org");
    }

    // ------------------------------------------------------------------ the text part

    /**
     * A message with an HTML part and no plain part is a spam signal on every filter
     * that looks, and we send from a domain whose reputation is worth more than the
     * few milliseconds this costs.
     */
    @Test
    @DisplayName("HTML with no text supplied still leaves as multipart/alternative with both parts")
    void textIsDerivedFromHtmlWhenNoneWasGiven() {
        mail.send(MAILBOX, Outgoing.message(List.of("donor@example.org"), List.of(), "Thank you",
                "<p>Thank you for <strong>everything</strong>.</p>"
                        + "<ul><li>Camp in Nashik</li><li>Camp in Pune</li></ul>", null));

        JsonNode email = jmap.argsFor("Email/set", "c0").path("create").path("draft");
        assertThat(email.path("bodyStructure").path("type").asString()).isEqualTo("multipart/alternative");

        String text = email.path("bodyValues").path("t").path("value").asString();
        assertThat(text)
                .contains("Thank you for everything.")
                .contains("- Camp in Nashik")
                .contains("- Camp in Pune")
                .doesNotContain("<");
    }

    @Test
    @DisplayName("a supplied text part is used unchanged and never regenerated")
    void suppliedTextWins() {
        mail.send(MAILBOX, Outgoing.message(List.of("donor@example.org"), List.of(), "Hello",
                "<p>Formatted</p>", "The plain wording I chose myself."));

        JsonNode email = jmap.argsFor("Email/set", "c0").path("create").path("draft");
        assertThat(email.path("bodyValues").path("t").path("value").asString())
                .isEqualTo("The plain wording I chose myself.");
    }

    @Test
    @DisplayName("a plain text message stays a single text/plain part")
    void plainTextStaysPlain() {
        mail.send(MAILBOX, Outgoing.message(List.of("donor@example.org"), List.of(),
                "Hello", null, "Just words."));

        JsonNode email = jmap.argsFor("Email/set", "c0").path("create").path("draft");
        assertThat(email.path("bodyStructure").path("type").asString()).isEqualTo("text/plain");
        assertThat(email.path("bodyValues").path("h").isMissingNode()).isTrue();
    }

    // ------------------------------------------------------------------ outbound markup

    /**
     * The client's own cleaning counts for nothing, because the client is where an
     * attacker already is. Everything below is markup a browser would happily post,
     * and none of it may reach the mail server.
     */
    @Test
    @DisplayName("hostile markup is rebuilt away before the message is handed to the mail server")
    void outboundHtmlIsSanitised() {
        String hostile = "<p onclick=\"steal()\">Hello <script>fetch('//evil')</script>"
                + "<a href=\"javascript:alert(1)\">click</a>"
                + "<a href=\"https://jarurat.care\">real</a>"
                + "<img src=\"https://tracker.example/pixel.gif\" onerror=\"x()\">"
                + "<style>body{display:none}</style>"
                + "<form><input name=\"card\"></form></p>";

        mail.send(MAILBOX, Outgoing.message(List.of("donor@example.org"), List.of(),
                "Hi", hostile, "Hello"));

        String html = jmap.argsFor("Email/set", "c0").path("create").path("draft")
                .path("bodyValues").path("h").path("value").asString();

        assertThat(html)
                .doesNotContain("script")
                .doesNotContain("javascript:")
                .doesNotContain("onclick")
                .doesNotContain("onerror")
                .doesNotContain("<style")
                .doesNotContain("<form")
                .doesNotContain("<input")
                .doesNotContain("card");
        // The legitimate half survives, because a sanitiser that eats the letter is
        // not safer, it is just useless.
        assertThat(html).contains("<a href=\"https://jarurat.care\">real</a>").contains("Hello");
        // And outbound we add none of the reader's anchor hardening.
        assertThat(html).doesNotContain("target=").doesNotContain("nofollow");
    }

    @Test
    @DisplayName("HTML over the ceiling is refused rather than truncated")
    void oversizedHtmlIsRefused() {
        String huge = "<p>" + "x".repeat(OutboundHtml.MAX_HTML) + "</p>";
        assertThatThrownBy(() -> mail.send(MAILBOX,
                Outgoing.message(List.of("a@example.org"), List.of(), "Big", huge, null)))
                .isInstanceOf(MailException.class)
                .hasMessageContaining("characters of HTML")
                .hasMessageContaining(String.valueOf(OutboundHtml.MAX_HTML));
        assertThat(jmap.sent("EmailSubmission/set")).isFalse();
    }

    // ------------------------------------------------------------------ threading

    /**
     * The two headers that decide whether a reply lands in the recipient's existing
     * conversation or opens a new one. Bare ids without angle brackets, because that
     * is the form RFC 8621 puts on the wire and Stalwart adds the brackets itself.
     */
    @Test
    @DisplayName("a reply carries In-Reply-To and a References chain ending in the parent")
    void replyCarriesThreadingHeaders() {
        mail.send(MAILBOX, Outgoing.message(List.of("hospital@tmc.gov.in"), List.of(),
                        "Re: Camp dates", null, "Confirmed.")
                .inThread("parent@tmc.gov.in", List.of("root@jarurat.care", "second@tmc.gov.in")));

        JsonNode email = jmap.argsFor("Email/set", "c0").path("create").path("draft");
        assertThat(strings(email.path("inReplyTo"))).containsExactly("parent@tmc.gov.in");
        assertThat(strings(email.path("references")))
                .containsExactly("root@jarurat.care", "second@tmc.gov.in", "parent@tmc.gov.in");
        assertThat(email.path("references").toString()).doesNotContain("<");
    }

    @Test
    @DisplayName("a long References chain is trimmed but keeps the message that started it")
    void referencesChainIsTrimmedFromTheSecondEntry() {
        List<String> chain = new ArrayList<>();
        chain.add("root@jarurat.care");
        for (int i = 1; i <= 40; i++) chain.add("m" + i + "@example.org");

        mail.send(MAILBOX, Outgoing.message(List.of("a@example.org"), List.of(), "Re: long", null, "ok")
                .inThread("parent@example.org", chain));

        List<String> refs = strings(jmap.argsFor("Email/set", "c0")
                .path("create").path("draft").path("references"));
        assertThat(refs).hasSize(20);
        assertThat(refs.get(0)).isEqualTo("root@jarurat.care");
        assertThat(refs.get(refs.size() - 1)).isEqualTo("parent@example.org");
    }

    @Test
    @DisplayName("a message that is not a reply carries neither header")
    void aNewMessageHasNoThreadHeaders() {
        mail.send(MAILBOX, Outgoing.message(List.of("a@example.org"), List.of(), "New", null, "Hi"));
        JsonNode email = jmap.argsFor("Email/set", "c0").path("create").path("draft");
        assertThat(email.has("inReplyTo")).isFalse();
        assertThat(email.has("references")).isFalse();
    }

    // ------------------------------------------------------------------ drafts

    /**
     * RFC 8621 makes an Email immutable apart from mailboxIds and keywords, so there
     * is no such thing as editing a draft. Replacing one is a create and a destroy in
     * a single Email/set, where the specified order is create first, so the new copy
     * exists before the old one stops existing.
     */
    @Test
    @DisplayName("saving over a draft creates the replacement and destroys the old one in one call")
    void savingOverADraftReplacesIt() {
        String id = mail.saveDraft(MAILBOX, "draft-old", Outgoing.message(
                List.of("donor@example.org"), List.of(), "Half written", "<p>So far</p>", null));

        assertThat(id).isEqualTo("email-new");
        ObjectNode args = jmap.argsFor("Email/set", "v0");
        assertThat(args.path("create").path("draft").path("mailboxIds").has("mb-drafts")).isTrue();
        assertThat(strings(args.path("destroy"))).containsExactly("draft-old");
        // A draft is not a submission and must never become one by accident.
        assertThat(jmap.sent("EmailSubmission/set")).isFalse();
    }

    @Test
    @DisplayName("a first save creates without destroying anything")
    void aFirstDraftSaveOnlyCreates() {
        mail.saveDraft(MAILBOX, null, Outgoing.message(List.of(), List.of(), "", "<p>a</p>", null));
        assertThat(jmap.argsFor("Email/set", "v0").has("destroy")).isFalse();
    }

    /**
     * The one place a Bcc header is legitimate. A draft is a file in the sender's own
     * mailbox that is never transmitted, so keeping the blind addresses on it is what
     * makes a draft written on a phone still know who it was going to blind copy when
     * it is opened on a laptop.
     */
    @Test
    @DisplayName("a draft keeps its blind copies, and sending the same message does not")
    void aDraftRemembersBlindCopiesAndASendDoesNot() {
        Outgoing message = Outgoing.message(List.of("a@example.org"), List.of(), "s", null, "t")
                .withBcc(List.of("trustee@jarurat.care"));

        mail.saveDraft(MAILBOX, null, message);
        assertThat(strings(jmap.argsFor("Email/set", "v0").path("create").path("draft")
                .path("bcc").findValues("email"))).containsExactly("trustee@jarurat.care");

        mail.send(MAILBOX, message);
        assertThat(jmap.argsFor("Email/set", "c0").path("create").path("draft").has("bcc")).isFalse();
    }

    // ------------------------------------------------------------------ recipients

    @Test
    @DisplayName("a message with no recipient at all is refused before anything is uploaded")
    void noRecipientIsRefused() {
        assertThatThrownBy(() -> mail.send(MAILBOX,
                Outgoing.message(List.of(), List.of(), "s", null, "t")))
                .isInstanceOf(MailException.class)
                .hasMessageContaining("at least one recipient");
    }

    @Test
    @DisplayName("more recipients than one message may carry is refused and points at Campaign Studio")
    void tooManyRecipientsAreRefused() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= Outgoing.MAX_RECIPIENTS; i++) many.add("person" + i + "@example.org");

        assertThatThrownBy(() -> mail.send(MAILBOX,
                Outgoing.message(many, List.of(), "s", null, "t")))
                .isInstanceOf(MailException.class)
                .hasMessageContaining("Campaign Studio");
        assertThat(jmap.sent("EmailSubmission/set")).isFalse();
    }

    @Test
    @DisplayName("a malformed address is refused by name")
    void aMalformedAddressIsNamed() {
        assertThatThrownBy(() -> mail.send(MAILBOX,
                Outgoing.message(List.of("fine@example.org", "broken@"), List.of(), "s", null, "t")))
                .isInstanceOf(MailException.class)
                .hasMessageContaining("broken@");
    }

    // ------------------------------------------------------------------ round trips

    /**
     * Sending used to cost three round trips before a byte moved: the identity list,
     * the folder list, and the message. Two of those ask for things that do not
     * change, so the second send in a session pays for one.
     */
    @Test
    @DisplayName("a second send costs one round trip instead of three")
    void identitiesAndFoldersAreNotRefetched() {
        Outgoing message = Outgoing.message(List.of("a@example.org"), List.of(), "s", null, "t");
        mail.send(MAILBOX, message);
        int afterFirst = jmap.requests.size();
        mail.send(MAILBOX, message);

        assertThat(afterFirst).isEqualTo(3);
        assertThat(jmap.requests.size() - afterFirst).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private List<String> envelopeRecipients() {
        return strings(jmap.argsFor("EmailSubmission/set", "s0")
                .path("create").path("sub").path("envelope").path("rcptTo").findValues("email"));
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) out.add(n.isString() ? n.asString() : n.toString());
        return out;
    }

    private static List<String> strings(List<JsonNode> nodes) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : nodes) out.add(n.isString() ? n.asString() : n.toString());
        return out;
    }

    private static List<String> namesIn(JsonNode node) {
        List<String> names = new ArrayList<>();
        collect(node, names);
        return names;
    }

    private static void collect(JsonNode node, List<String> into) {
        if (node.isObject()) {
            for (java.util.Map.Entry<String, JsonNode> e : node.properties()) {
                into.add(e.getKey());
                collect(e.getValue(), into);
            }
        } else if (node.isArray()) {
            for (JsonNode n : node) collect(n, into);
        }
    }
}
