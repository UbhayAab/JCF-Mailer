package com.jarurat.mailer.campaignsplus;

import com.jarurat.mailer.services.SesSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two checks the Templates screen now shows on every row, pinned.
 *
 * The clip threshold is the one that costs money to get wrong. Gmail truncates a
 * message past roughly 102 KB and hides the rest behind a "View entire message"
 * link, and the unsubscribe footer is at the end. A charity whose unsubscribe
 * link is invisible to the largest mail client in its list is looking at a
 * deliverability problem and a compliance one at the same time, so the boundary
 * is tested on both sides of itself rather than trusted to a comment.
 *
 * The size that decides it is the size of the RENDERED message, not of the
 * template body. Tracking rewrites every link, the open pixel goes in, the
 * unsubscribe footer is appended and the preheader is injected, and all of that
 * is on the far side of the number a human sees in the editor. A test that
 * measured the raw body would agree with itself and disagree with Gmail.
 */
class TemplateLibraryValidationTest {

    /* validate(), sample(null) and mergeFieldsOf() touch no repository: they are
       pure functions of their arguments and the renderer. Passing nulls for the
       three repositories is deliberate, so that a change which starts reading the
       database inside validation fails here loudly rather than quietly costing a
       query per row on a screen that validates the whole library at once. */
    private final SesSender ses = new SesSender("ap-south-1", "hello@example.com", "Jarurat Care",
            "reply@example.com", 12, "", "https://mailer.example.com");
    private final TemplateLibraryService library = new TemplateLibraryService(null, null, null, ses);

    private TemplateLibraryService.Validation validate(String subject, String html) {
        return library.validate(subject, html, "MARKETING", null, library.sample(null), true, true);
    }

    /** A body whose rendered form lands within a few bytes of the given size. */
    private String bodyOfRenderedSize(int target) {
        String head = "<html><body><p>Dear {{FIRST_NAME}}</p><p>";
        String tail = "</p></body></html>";
        int overhead = validate("Hello", head + tail).renderedBytes();
        return head + "x".repeat(Math.max(0, target - overhead)) + tail;
    }

    // ------------------------------------------------------------------
    // The Gmail clip boundary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the threshold is 102 KB, the size Gmail actually clips at")
    void thresholdIsWhatGmailUses() {
        assertThat(TemplateLibraryService.GMAIL_CLIP_BYTES).isEqualTo(104448);
        assertThat(validate("Hello", "<p>Hi</p>").limitBytes())
                .isEqualTo(TemplateLibraryService.GMAIL_CLIP_BYTES);
    }

    @Test
    @DisplayName("exactly on the threshold is not clipped")
    void exactlyOnTheLimitIsAllowed() {
        // The comparison is strictly greater than, so the boundary byte itself is
        // still a message Gmail shows whole. Flipping this to >= would put a
        // warning on the one email that does not need one.
        TemplateLibraryService.Validation v =
                validate("Hello", bodyOfRenderedSize(TemplateLibraryService.GMAIL_CLIP_BYTES));

        assertThat(v.renderedBytes()).isEqualTo(TemplateLibraryService.GMAIL_CLIP_BYTES);
        assertThat(v.overGmailClip()).isFalse();
        assertThat(codes(v)).doesNotContain("OVER_GMAIL_CLIP");
    }

    @Test
    @DisplayName("one byte over the threshold is clipped, and says so")
    void oneByteOverIsFlagged() {
        TemplateLibraryService.Validation v =
                validate("Hello", bodyOfRenderedSize(TemplateLibraryService.GMAIL_CLIP_BYTES + 1));

        assertThat(v.renderedBytes()).isEqualTo(TemplateLibraryService.GMAIL_CLIP_BYTES + 1);
        assertThat(v.overGmailClip()).isTrue();
        assertThat(codes(v)).contains("OVER_GMAIL_CLIP");
        assertThat(messageFor(v, "OVER_GMAIL_CLIP"))
                .contains("102.0 KB")
                .contains("unsubscribe");
    }

    @Test
    @DisplayName("a small template is nowhere near the threshold")
    void ordinaryTemplateIsClear() {
        TemplateLibraryService.Validation v =
                validate("Your April update", "<html><body><p>Dear {{FIRST_NAME}}</p></body></html>");

        assertThat(v.overGmailClip()).isFalse();
        assertThat(v.renderedBytes()).isLessThan(4096);
    }

    @Test
    @DisplayName("the size measured is the rendered size, not the size of the body")
    void sizeIsMeasuredAfterRendering() {
        // The tracking pixel, the rewritten link and the appended unsubscribe footer
        // are all added by the renderer. A template sitting just under the limit in
        // the editor can cross it once those land, which is exactly the case a check
        // on the raw body would miss.
        String body = "<html><body><p>Hi</p><a href=\"https://jarurat.care/donate\">Donate</a></body></html>";
        TemplateLibraryService.Validation v = validate("Hello", body);

        assertThat(v.renderedBytes()).isGreaterThan(body.getBytes(StandardCharsets.UTF_8).length);
        assertThat(v.hasUnsubscribeLink()).isTrue();
    }

    @Test
    @DisplayName("bytes are counted in UTF-8, so Devanagari costs what it costs")
    void multiByteCharactersAreCountedAsBytes() {
        // A Hindi creative is three bytes per character where the English one is one.
        // Counting characters would report a third of the true size and clear a
        // message Gmail clips, on exactly the list most likely to be in Hindi.
        String hindi = "<html><body><p>" + "न".repeat(1000) + "</p></body></html>";
        String latin = "<html><body><p>" + "n".repeat(1000) + "</p></body></html>";

        assertThat(validate("Hello", hindi).renderedBytes())
                .isEqualTo(validate("Hello", latin).renderedBytes() + 2000);
    }

    // ------------------------------------------------------------------
    // Merge field extraction
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the fields listed are the ones the copy actually uses, in reading order")
    void extractsFieldsInReadingOrder() {
        assertThat(library.mergeFieldsOf("{{FIRST_NAME}}, your April update",
                "<p>Dear {{NAME}}, see you in {{CITY}}</p>"))
                .containsExactly("FIRST_NAME", "NAME", "CITY");
    }

    @Test
    @DisplayName("a preheader tag is a field too")
    void preheaderIsScanned() {
        // The grey line under the subject is the second thing a recipient reads. A
        // raw {{CITY}} sitting in it was invisible to this check until the preheader
        // overload existed, so the overload is what the screen calls.
        assertThat(library.mergeFieldsOf("Your update", "A note for {{CITY}}", "<p>Hello</p>"))
                .containsExactly("CITY");
    }

    @Test
    @DisplayName("the sender's own tags are never listed as fields to supply")
    void reservedTagsAreNotFields() {
        assertThat(library.mergeFieldsOf("Hello",
                "<a href=\"{{UNSUBSCRIBE_LINK}}\">Stop</a> {{NAME}}"))
                .containsExactly("NAME");
    }

    @Test
    @DisplayName("the same field written three ways is one field")
    void spellingsCollapse() {
        assertThat(library.mergeFieldsOf("{{name}}", "<p>{{NAME}} {{ Name }}</p>"))
                .containsExactly("NAME");
    }

    @Test
    @DisplayName("a field a campaign send cannot fill is reported as unresolved")
    void unresolvedFieldsAreNamed() {
        // A campaign fills NAME, FIRST_NAME and EMAIL and nothing else. CITY renders
        // as an empty string to every recipient, which on screen is the difference
        // between "3 merge fields" and "one of these will be blank in every copy".
        TemplateLibraryService.Validation v =
                validate("Hello {{FIRST_NAME}}", "<html><body><p>See you in {{CITY}}</p></body></html>");

        assertThat(v.mergeFields()).containsExactly("FIRST_NAME", "CITY");
        assertThat(v.unresolvedFields()).containsExactly("CITY");
        assertThat(codes(v)).contains("UNRESOLVED_FIELDS");
        assertThat(messageFor(v, "UNRESOLVED_FIELDS")).contains("CITY").contains("render empty");
    }

    @Test
    @DisplayName("a template that only uses fillable fields has nothing unresolved")
    void resolvedFieldsAreNotFlagged() {
        TemplateLibraryService.Validation v =
                validate("Hello {{FIRST_NAME}}", "<html><body><p>Dear {{NAME}}</p></body></html>");

        assertThat(v.mergeFields()).containsExactly("FIRST_NAME", "NAME");
        assertThat(v.unresolvedFields()).isEmpty();
        assertThat(codes(v)).doesNotContain("UNRESOLVED_FIELDS");
    }

    @Test
    @DisplayName("a mistyped tag is caught by the brace count, not by the field list")
    void unbalancedBracesAreFlagged() {
        // {{NAME} matches no tag at all, so it is invisible to extraction and ships
        // to the recipient as literal text. Counting braces is what sees it.
        TemplateLibraryService.Validation v =
                validate("Hello", "<html><body><p>Dear {{NAME}</p></body></html>");

        assertThat(v.mergeFields()).isEmpty();
        assertThat(codes(v)).contains("UNBALANCED_MERGE_TAGS");
    }

    @Test
    @DisplayName("no merge fields is a clean answer, not an empty one")
    void noFieldsIsFine() {
        assertThat(library.mergeFieldsOf("Your April update", "<p>Nothing to merge here.</p>")).isEmpty();
        assertThat(library.mergeFieldsOf(null, null)).isEmpty();
    }

    // ------------------------------------------------------------------

    private static List<String> codes(TemplateLibraryService.Validation v) {
        return v.issues().stream().map(TemplateLibraryService.Issue::code).toList();
    }

    private static String messageFor(TemplateLibraryService.Validation v, String code) {
        return v.issues().stream()
                .filter(i -> code.equals(i.code()))
                .map(TemplateLibraryService.Issue::message)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no issue with code " + code));
    }
}
