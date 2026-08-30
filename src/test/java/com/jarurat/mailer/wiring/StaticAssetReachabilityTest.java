package com.jarurat.mailer.wiring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails when a script exists and no page loads it.
 *
 * This is the one test in the tree written against a mistake that actually
 * happened, five separate times, rather than against a requirement. Each time the
 * shape was identical: a feature was written, tested, reviewed and deployed, and
 * then referenced by nothing, so the person who asked for it opened the product,
 * saw no change, and reported that it had never been built. They were right every
 * time. mailsettings.js was 78,030 bytes and three authors added work to it while
 * it was unreachable; templates.js was 704 lines and shipped one release with no
 * script tag at all.
 *
 * The reason ordinary tests do not catch this is that they exercise one side of a
 * join. A unit test proves the module works. An integration test proves the
 * endpoint answers. Neither one asks whether a browser is ever told to fetch the
 * file, because that fact lives in a Thymeleaf template that no Java test reads.
 *
 * So this reads the templates as text. It is deliberately dumb: a substring match
 * on the file name, no HTML parsing, no attempt to prove the tag is well formed or
 * that the code inside runs. A file name that appears nowhere is a certainty; a
 * file name that appears somewhere is only evidence, and the click-through harness
 * is what checks the rest.
 */
class StaticAssetReachabilityTest {

    private static final Path JS = Path.of("src/main/resources/static/js");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path STATIC = Path.of("src/main/resources/static");

    /**
     * The service worker is the one honest exception. Nothing links it with a
     * script tag, by design: it is fetched by navigator.serviceWorker.register,
     * which passes its path as a string. Named here rather than pattern matched,
     * so a second exception has to be argued for in a diff.
     */
    private static final List<String> REGISTERED_NOT_LINKED = List.of("sw.js");

    @Test
    @DisplayName("every script under static/js is named by at least one page")
    void noOrphanedScripts() throws IOException {
        List<String> orphans = new ArrayList<>();

        try (Stream<Path> files = Files.list(JS)) {
            for (Path js : files.filter(p -> p.toString().endsWith(".js")).toList()) {
                String name = js.getFileName().toString();
                if (REGISTERED_NOT_LINKED.contains(name)) continue;

                // Excluding the file being checked is the entire correctness of this
                // test. Nearly every script in this tree opens with a header comment
                // naming itself, so a haystack that includes the candidate matches it
                // against its own first line and passes unconditionally. The first
                // version of this test did exactly that: templates.js was orphaned,
                // the test was run against the orphaned tree on purpose, and it went
                // green. A guard that cannot go red is decoration.
                boolean referenced = readAllExcept(js).stream().anyMatch(t -> t.contains(name));
                if (!referenced) orphans.add(name);
            }
        }

        assertThat(orphans)
                .as("These scripts are in the build and no page loads them, so every line "
                    + "in them is dead on the deployed site. Add a script tag to the page "
                    + "that needs each one, or delete the file. Do not silence this by "
                    + "adding the name to REGISTERED_NOT_LINKED unless something really "
                    + "does fetch it by string at runtime.")
                .isEmpty();
    }

    /** A page that loads a script that is not in the build is the same defect inverted. */
    @Test
    @DisplayName("every script a page asks for exists on disk")
    void noBrokenScriptReferences() throws IOException {
        List<String> missing = new ArrayList<>();

        for (Path page : pages(TEMPLATES)) {
            String html = Files.readString(page);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("src=\"/js/([A-Za-z0-9_.-]+[.]js)")
                    .matcher(html);
            while (m.find()) {
                String name = m.group(1);
                if (!Files.exists(JS.resolve(name))) {
                    missing.add(page.getFileName() + " asks for /js/" + name);
                }
            }
        }

        assertThat(missing)
                .as("A page loads a script that is not in the build. The browser gets a 404, "
                    + "the rest of that page's JavaScript may never run, and nothing else in "
                    + "this suite would notice.")
                .isEmpty();
    }

    /** Every template and every static file except one, as raw text. */
    private static List<String> readAllExcept(Path skip) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path p : pages(TEMPLATES)) out.add(Files.readString(p));
        try (Stream<Path> s = Files.walk(STATIC)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                if (skip != null && Files.isSameFile(p, skip)) continue;
                String n = p.toString();
                if (n.endsWith(".js") || n.endsWith(".html") || n.endsWith(".json")) {
                    out.add(Files.readString(p));
                }
            }
        }
        return out;
    }

    private static List<Path> pages(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".html"))
                    .toList();
        }
    }
}
