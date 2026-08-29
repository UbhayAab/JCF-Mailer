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
    void senderHtmlIsRenderedOnWhiteEvenWhenTheAppAsksForDark() {
        String doc = MailHtmlSanitizer
                .toReaderDocument(NEWSLETTER, null, false, "dark").html();

        // The sender's own colours must survive, because stripping them mangles every
        // newsletter and every signature. What changes is the ground under them.
        assertThat(doc).contains("#333333");
        assertThat(doc).contains("--b:#ffffff");
        assertThat(doc).doesNotContain("--b:#202020");
        assertThat(doc).contains("color-scheme:light");
    }

    /** The same must hold for "auto", which follows the reader's operating system. */
    @Test
    void senderHtmlIgnoresAutoThemeToo() {
        String doc = MailHtmlSanitizer
                .toReaderDocument(NEWSLETTER, null, false, "auto").html();

        assertThat(doc).contains("--b:#ffffff");
        // No prefers-color-scheme block, or a machine set to dark reintroduces the bug
        // for exactly the people who would never think to report it.
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

        assertThat(doc).contains("--b:#202020");
        assertThat(doc).contains("color-scheme:dark");
        assertThat(doc).contains("jc-plain");
        // The charcoal the console uses, not the blue slate this frame used to carry.
        assertThat(doc).doesNotContain("#121c22");
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
