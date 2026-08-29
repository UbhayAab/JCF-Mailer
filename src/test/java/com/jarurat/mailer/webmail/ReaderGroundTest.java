package com.jarurat.mailer.webmail;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reported bug: the reader showed messages that looked empty because the text
 * was black on a black ground.
 *
 * The cause is not a missing colour. It is that this application deliberately keeps
 * the sender's own colours, and almost every HTML mail ever written sets a dark text
 * colour and no background at all, because mail clients have always shown white.
 * Painting that on the dark shell produces near-black on near-black.
 *
 * These pin the rule that fixes it: a body the SENDER wrote is rendered on white,
 * whatever theme the app asks for; a plain text body, whose markup this application
 * generates itself and which therefore carries no colours, follows the app theme.
 */
class ReaderGroundTest {

    /** What a real newsletter looks like: a text colour, and no background anywhere. */
    private static final String NEWSLETTER = """
            <div style="font-family:Arial,sans-serif;color:#333333">
              <h1 style="color:#222">Monthly update from the referral desk</h1>
              <p style="color:#444444">Dear Anita,</p>
              <p><font color="#000000">The oncology desk has confirmed the referral.</font></p>
              <table><tr><td style="color:#111">Building C, 10:15</td></tr></table>
            </div>
            """;

    @Test
    void senderHtmlReadsOnTheDarkGroundWithItsColoursLifted() {
        String doc = MailHtmlSanitizer
                .toReaderDocument(NEWSLETTER, null, false, "dark").html();

        // The sender's own colours must survive, because stripping them mangles every
        // newsletter and every signature. What changes is the ground under them.
        // Rendering the message on white was the first answer, and it is what Gmail
        // does, but a white slab inside a dark application is jarring. The ground now
        // stays dark and the colours the sender chose against white are re-tuned.
        assertThat(doc).contains("body{background:#202020");
        assertThat(doc).contains("color-scheme:dark");
        // The near-black body colour cannot survive as written or it is invisible.
        assertThat(doc).doesNotContain("#333333");
    }

    /** The same must hold for "auto", which follows the reader's operating system. */
    @Test
    void senderHtmlIgnoresAutoThemeToo() {
        String doc = MailHtmlSanitizer
                .toReaderDocument(NEWSLETTER, null, false, "auto").html();

        assertThat(doc).contains("body{background:#202020");
        // No prefers-color-scheme anywhere, ours or the sender's. color-scheme pins what
        // the frame may render but does NOT stop it answering the media query, so a
        // sender's dark-mode block still fired against our forced white ground and
        // painted white on white. Measured at 1.00:1 before this.
        assertThat(doc).doesNotContain("prefers-color-scheme");
    }

    /**
     * A plain text body is markup this class generates, with no colours of its own, so
     * it follows the shell and looks native rather than being a white slab in a dark app.
     */
    @Test
    void aPlainTextBodyStillFollowsTheAppTheme() {
        String doc = MailHtmlSanitizer
                .toReaderDocument(null, "Just a plain note.\nNo HTML at all.", false, "dark").html();

        assertThat(doc).contains("body{background:#202020");
        assertThat(doc).contains("color-scheme:dark");
        assertThat(doc).contains("jc-plain");
        // The charcoal the console uses, not the blue slate this frame used to carry.
        assertThat(doc).doesNotContain("#121c22");
    }

    /**
     * The second route to the same bug, which survived the first fix.
     *
     * Forcing the ground to white does not stop the frame answering
     * prefers-color-scheme: dark, because color-scheme declares what a page is willing
     * to render, not what the media query reports. Every current email builder emits a
     * dark-mode block, so on a machine set to dark the sender's own rules painted white
     * text onto our forced white paper. Measured at 1.00:1, one distinct colour across
     * the whole painted region, which reads as an empty message.
     *
     * The frame is therefore made to behave as though the machine were light: the dark
     * branch cannot apply and is dropped, and the light branch always applies and is
     * unwrapped so it fires even when the reader's machine is dark.
     */
    @Test
    void aSenderDarkModeBlockCannotPaintOnTheForcedWhiteGround() {
        String builderOutput = """
                <style>
                  .h{color:#222222} .t{color:#333333}
                  @media (prefers-color-scheme: dark){ .h{color:#ffffff} .t{color:#e8e8e8} }
                  @media (prefers-color-scheme: light){ .t{font-weight:600} }
                </style>
                <div><p class="h">Your payslip</p><p class="t">Raise any discrepancy.</p></div>
                """;
        String doc = MailHtmlSanitizer.toReaderDocument(builderOutput, null, false, "dark").html();

        // The light-branch colours are what survive, lifted for the dark ground, so
        // the assertion is that the dark-branch ones are absent rather than that the
        // originals are present unchanged.
        assertThat(doc).doesNotContain("#222222").doesNotContain("#333333");
        assertThat(doc).doesNotContain("#e8e8e8");          // the dark branch is gone
        assertThat(doc).contains("font-weight:600");        // the light branch still fires
        assertThat(doc).doesNotContain("prefers-color-scheme");
    }

    /**
     * A sender's stylesheet is emitted inside the body, after the wrapper's own rules,
     * so anything the wrapper expresses as a custom property can be redefined by the
     * message being rendered. The ground and the default text colour are therefore
     * written as literals, and this pins that.
     */
    @Test
    void aSenderCannotRepaintTheGroundThroughTheWrappersOwnVariables() {
        String hijack = """
                <style>:root{--jcb:#151515;--jct:#181818;--b:#151515;--t:#181818}</style>
                <div><p>Text that tried to repaint the ground under itself.</p></div>
                """;
        String doc = MailHtmlSanitizer.toReaderDocument(hijack, null, false, "dark").html();

        assertThat(doc).contains("body{background:#202020");
        assertThat(doc).doesNotContain("body{background:var(");
    }

    /**
     * The reverse case, which a naive "force white" fix breaks: a sender who sets a
     * dark background AND light text. Their pairing has to survive intact.
     */
    @Test
    void aSenderWhoBringsBothColoursKeepsThem() {
        String dark = """
                <div style="background:#101418;color:#f5f5f5">
                  <p style="color:#ffffff">Light text the sender chose.</p>
                </div>
                """;
        String doc = MailHtmlSanitizer.toReaderDocument(dark, null, false, "dark").html();

        assertThat(doc).contains("#101418");
        assertThat(doc).contains("#f5f5f5");
    }

    /**
     * Writes the real rendered document out so it can be opened and looked at, because
     * this whole class of bug is invisible in a passing assertion and obvious in a
     * screenshot. Not an assertion of appearance, just a way to see it.
     */
    @Test
    void writeTheRenderedDocumentForInspection() throws Exception {
        Path out = Path.of(System.getProperty("java.io.tmpdir"), "jc-reader-proof");
        Files.createDirectories(out);
        Files.writeString(out.resolve("newsletter-dark-theme.html"),
                MailHtmlSanitizer.toReaderDocument(NEWSLETTER, null, false, "dark").html());
        Files.writeString(out.resolve("plaintext-dark-theme.html"),
                MailHtmlSanitizer.toReaderDocument(null,
                        "Plain text note.\nSecond line.\nhttps://jarurat.care", false, "dark").html());
        assertThat(out.resolve("newsletter-dark-theme.html")).exists();
    }
}
