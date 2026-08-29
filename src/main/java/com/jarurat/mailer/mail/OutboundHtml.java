package com.jarurat.mailer.mail;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The gate every byte of composer HTML passes through on its way to the mail server.
 *
 * This is a second sanitiser and not a call into MailHtmlSanitizer, which deserves an
 * argument rather than an apology. The reader's policy is not merely unused outbound,
 * it is actively wrong in six ways and four of them lose the sender's own work rather
 * than open a hole. It appends target="_blank" rel="noopener noreferrer nofollow" to
 * every anchor, which is a statement about somebody else's link that makes no sense on
 * our own and which Outlook's Word engine discards anyway after we have paid for the
 * bytes. Its remote image flag defaults to the destructive value and rewrites the
 * sender's own pictures to a blank pixel. It keeps a style element, which Outlook
 * ignores and Gmail bills against the 102KB it clips a message at, so the mail would
 * look right in our reader and wrong in the recipient's. It deletes a
 * prefers-color-scheme block, which is a legibility decision belonging to whoever is
 * reading and not to whoever is writing. It truncates silently at two million
 * characters, and posting the stump of a letter somebody typed is worse than refusing
 * to send it. And its scheme list has no cid, which is how an inline image is named.
 * Sharing one class would mean threading a policy record through a tokeniser this
 * agent does not own while other agents are editing that file, so the honest split is
 * a second policy here, in the package that talks to the mail server, applied at the
 * last point before the JSON is built.
 *
 * The direction of the check is inverted on purpose. Inbound the problem is arbitrary
 * markup from a stranger, so the reader blocks what is known to be poison. Outbound we
 * own the producer, so this permits only what our own composer can emit and unwraps
 * everything else. That is the stronger guarantee and it costs nothing, because there
 * is no legitimate outbound element this list does not name.
 *
 * The deliverability half matters as much as the security half. We send from a domain
 * with SES production access and a reputation that took months to earn, and obfuscated
 * legacy markup, a script that never runs, a form nobody can submit and a stylesheet
 * no client reads are all things a spam filter scores against the sending domain
 * rather than against the individual message.
 */
public final class OutboundHtml {

    private OutboundHtml() {
    }

    /**
     * The ceiling on one composed HTML part, and it is a refusal rather than a trim.
     *
     * Gmail hides everything past 102KB of raw message behind a "View entire message"
     * link, and a letter plus its quoted history plus the base64 overhead has to fit
     * under that or the recipient reads half of it. Sixty thousand characters of HTML
     * leaves room for the quote, the headers and the encoding, and is far more than
     * anybody types into a compose sheet by hand.
     */
    public static final int MAX_HTML = 60_000;

    /**
     * Elements that survive, after renaming. Everything absent from this set is
     * unwrapped rather than deleted, so a tag we did not anticipate costs the sender
     * its formatting and never its words.
     */
    private static final Set<String> ALLOWED = Set.of(
            "p", "br", "div", "strong", "em", "u", "s", "a", "ul", "ol", "li",
            "blockquote", "h1", "h2", "h3", "h4", "hr", "pre", "code", "img",
            "table", "thead", "tbody", "tfoot", "tr", "td", "th");

    /**
     * The same meaning under a different name. b and i render in every client, but
     * strong and em are what a screen reader announces as emphasis, and the recipient
     * who is listening to their mail is the reason to prefer them.
     */
    private static final Map<String, String> RENAME = Map.of(
            "b", "strong", "i", "em", "strike", "s", "del", "s", "ins", "u", "mark", "strong");

    private static final Set<String> VOID = Set.of("br", "hr", "img");

    /**
     * Elements whose contents go with them. Everywhere else this class unwraps and
     * keeps the text, but the text inside a script or a style is code and the text
     * inside a title or a form control is chrome, and none of it is anything the
     * sender typed into the body of a letter.
     */
    private static final Set<String> DROP_SUBTREE = Set.of(
            "script", "style", "iframe", "frame", "frameset", "object", "embed", "applet",
            "noscript", "template", "svg", "math", "head", "title", "meta", "link", "base",
            "form", "input", "button", "select", "option", "textarea", "canvas", "audio",
            "video", "source", "track", "map", "area", "xml");

    /** Global attributes. Deliberately one, because a class or an id has nothing to point at. */
    private static final Set<String> GLOBAL_ATTRS = Set.of("style");

    private static final Map<String, Set<String>> EXTRA_ATTRS = Map.of(
            "a", Set.of("href", "title"),
            "img", Set.of("src", "alt", "width", "height"),
            "td", Set.of("colspan", "rowspan", "align", "valign", "width"),
            "th", Set.of("colspan", "rowspan", "align", "valign", "width"),
            "table", Set.of("width", "align", "border", "cellpadding", "cellspacing"),
            "ol", Set.of("start"));

    /**
     * Schemes an outbound link may carry. cid is absent and its absence is a
     * dependency rather than an oversight: MailService marks every part it builds as
     * an attachment and never emits a Content-ID, so a cid reference would arrive at
     * the recipient pointing at nothing. Adding cid here without multipart/related in
     * MailService would ship a broken picture rather than no picture.
     */
    private static final Set<String> LINK_SCHEMES = Set.of("http", "https", "mailto", "tel");

    /** Image formats with no markup layer, so nothing can hide a script inside one. */
    private static final List<String> INLINE_IMAGE_TYPES =
            List.of("data:image/png", "data:image/jpeg", "data:image/jpg",
                    "data:image/gif", "data:image/webp");

    /**
     * The declarations a mail client will honour, which is a much shorter list than
     * the ones a browser understands. An allowlist rather than the reader's poison
     * check, because outbound we know exactly what our own serialiser produces, and
     * anything outside this set is either something no mail client renders or
     * something only an attacker wants.
     */
    private static final Set<String> CSS_PROPERTIES = Set.of(
            "color", "background-color", "font-weight", "font-style", "font-family", "font-size",
            "text-decoration", "text-decoration-line", "text-align", "line-height", "letter-spacing",
            "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
            "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
            "border", "border-top", "border-right", "border-bottom", "border-left",
            "border-color", "border-width", "border-style", "border-radius", "border-collapse",
            "border-spacing", "width", "max-width", "min-width", "height", "list-style-type",
            "vertical-align", "white-space", "display");

    /** The only display values worth carrying. Anything else is layout no mail client agrees on. */
    private static final Set<String> DISPLAY_VALUES =
            Set.of("block", "inline", "inline-block", "none", "list-item",
                    "table", "table-row", "table-cell");

    /**
     * A ceiling on nesting, because a list nested a thousand deep is not a letter and
     * rebuilding it would cost a thousand closing tags on the way back out.
     */
    private static final int MAX_DEPTH = 48;

    // ------------------------------------------------------------------
    // Cleaning
    // ------------------------------------------------------------------

    /**
     * Rebuilds composer markup as the subset above and discards everything else.
     *
     * The result is a fragment and never a document, and it is built by writing new
     * tags rather than by editing the ones that arrived, so nothing survives that this
     * method did not choose to write. Refuses anything over MAX_HTML instead of
     * truncating it, because half a letter that claims to have been sent is a worse
     * outcome than an error the sender can act on.
     */
    public static String clean(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) return "";
        if (rawHtml.length() > MAX_HTML) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "That message is " + rawHtml.length() + " characters of HTML and the limit is "
                            + MAX_HTML + ". Shorten it or trim the quoted history, because Gmail "
                            + "hides anything past this behind a link.");
        }

        String src = rawHtml;
        int n = src.length();
        StringBuilder out = new StringBuilder(n + 64);
        Deque<String> open = new ArrayDeque<>();

        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c != '<') {
                int end = src.indexOf('<', i);
                if (end < 0) end = n;
                escapeText(src, i, end, out);
                i = end;
                continue;
            }
            if (src.startsWith("<!--", i)) {
                int end = src.indexOf("-->", i + 4);
                i = end < 0 ? n : end + 3;
                continue;
            }
            if (src.startsWith("<!", i) || src.startsWith("<?", i)) {
                int end = src.indexOf('>', i);
                i = end < 0 ? n : end + 1;
                continue;
            }

            Tag tag = parseTag(src, i);
            if (tag == null) {
                // A bare "<" that starts no tag is a character the sender typed.
                out.append("&lt;");
                i++;
                continue;
            }
            i = tag.end();

            String name = tag.name();
            // A namespaced element is Word's and never a composer's, and o:p is the
            // one that arrives inside every paste out of Outlook.
            if (name.indexOf(':') >= 0 || DROP_SUBTREE.contains(name)) {
                if (!tag.closing() && !tag.selfClosing()) i = skipSubtree(src, i, name);
                continue;
            }

            String mapped = RENAME.getOrDefault(name, name);
            if (!ALLOWED.contains(mapped)) continue;

            if (tag.closing()) {
                if (!open.contains(mapped)) continue;
                String popped;
                do {
                    popped = open.pop();
                    out.append("</").append(popped).append('>');
                } while (!popped.equals(mapped));
                continue;
            }

            if (open.size() >= MAX_DEPTH) continue;

            out.append('<').append(mapped);
            writeAttributes(mapped, tag.attrs(), out);
            out.append('>');
            if (!VOID.contains(mapped)) open.push(mapped);
        }

        while (!open.isEmpty()) out.append("</").append(open.pop()).append('>');
        return out.toString();
    }

    private static void writeAttributes(String tag, Map<String, String> attrs, StringBuilder out) {
        Set<String> extra = EXTRA_ATTRS.getOrDefault(tag, Set.of());
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            String key = e.getKey();
            if (!GLOBAL_ATTRS.contains(key) && !extra.contains(key)) continue;
            String value = decodeEntities(e.getValue());

            String written = switch (key) {
                case "style" -> safeStyle(value);
                case "href" -> safeUrl(value, false);
                case "src" -> safeUrl(value, true);
                case "width", "height", "colspan", "rowspan", "border",
                        "cellpadding", "cellspacing", "start" -> digits(value);
                case "align", "valign" -> word(value);
                default -> plain(value);
            };
            if (written == null || written.isEmpty()) continue;

            out.append(' ').append(key).append("=\"");
            escapeAttr(written, out);
            out.append('"');
        }
        // No target and no rel, which is the clearest difference from the reader's
        // policy. Both are things you say about a link somebody else wrote, Outlook's
        // Word engine drops target on the way in, and rel="nofollow" on our own link
        // tells the recipient's client we do not vouch for where we just sent them.
    }

    /** Keeps only the declarations a mail client honours, and only where the value is inert. */
    static String safeStyle(String raw) {
        if (raw == null || raw.isBlank()) return "";
        StringBuilder css = new StringBuilder();
        for (String decl : raw.split(";")) {
            int colon = decl.indexOf(':');
            if (colon <= 0) continue;
            String property = decl.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = decl.substring(colon + 1).trim();
            if (value.isEmpty() || !CSS_PROPERTIES.contains(property)) continue;

            // No allowed property takes a url or a second colon, so a parenthesis or a
            // colon here is either an expression, an image request that would act as a
            // read receipt for us, or an escape attempt, and none of the three has an
            // innocent reading in a letter.
            String folded = value.toLowerCase(Locale.ROOT);
            if (folded.indexOf('(') >= 0 || folded.indexOf('\\') >= 0 || folded.indexOf('<') >= 0
                    || folded.indexOf(':') >= 0 || folded.indexOf('@') >= 0
                    || folded.contains("expression")) {
                continue;
            }
            if (property.equals("display") && !DISPLAY_VALUES.contains(folded)) continue;
            if (value.length() > 200) continue;

            if (!css.isEmpty()) css.append(';');
            css.append(property).append(':').append(value);
        }
        return css.toString();
    }

    /**
     * A URL we are willing to put our sending domain's name behind.
     *
     * Whitespace and control characters come out before the scheme is read, because
     * "java\nscript:" is the oldest way there is of making a scheme look like
     * something else, and the entity decode has already run by the time this is
     * called, so the same trick spelled &amp;#106; meets the same check.
     */
    static String safeUrl(String raw, boolean image) {
        if (raw == null) return null;
        StringBuilder cleaned = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c <= 0x20 || c == 0x7f) continue;
            cleaned.append(c);
        }
        String url = cleaned.toString();
        if (url.isEmpty()) return null;

        String folded = url.toLowerCase(Locale.ROOT);
        if (image) {
            for (String type : INLINE_IMAGE_TYPES) {
                if (folded.startsWith(type + ";base64,")) return url;
            }
        }
        int colon = folded.indexOf(':');
        if (colon <= 0) return null;
        String scheme = folded.substring(0, colon);
        if (image) return scheme.equals("http") || scheme.equals("https") ? url : null;
        return LINK_SCHEMES.contains(scheme) ? url : null;
    }

    private static String digits(String v) {
        String t = v.trim();
        String bare = t.endsWith("%") ? t.substring(0, t.length() - 1) : t;
        if (bare.isEmpty() || bare.length() > 6) return null;
        for (int i = 0; i < bare.length(); i++) {
            if (!Character.isDigit(bare.charAt(i))) return null;
        }
        return t;
    }

    private static String word(String v) {
        String t = v.trim().toLowerCase(Locale.ROOT);
        return t.matches("[a-z]{1,10}") ? t : null;
    }

    private static String plain(String v) {
        String t = v.trim();
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    // ------------------------------------------------------------------
    // The text alternative
    // ------------------------------------------------------------------

    /**
     * The plain part, built by walking the markup rather than by stripping tags out of
     * it with a regular expression.
     *
     * A message with an HTML part and no text part scores against the sending domain
     * in every filter that looks, so this is a deliverability control before it is a
     * courtesy to whoever reads their mail in a terminal. The walk is what makes it
     * worth having over a tag strip: a list arrives as a list, a quote arrives quoted,
     * and a link whose text is not its address arrives with the address beside it.
     * Runs on the already cleaned HTML, so it can never see the inside of a script
     * that clean() has already thrown away.
     */
    public static String toText(String html) {
        if (html == null || html.isBlank()) return "";
        StringBuilder out = new StringBuilder(html.length());
        Deque<Integer> quotes = new ArrayDeque<>();
        Deque<Integer> items = new ArrayDeque<>();
        // Each list on the stack is {ordered, nextNumber}, so a numbered list keeps
        // counting from where a nested bulleted list interrupted it.
        Deque<int[]> lists = new ArrayDeque<>();
        String href = null;
        int anchorFrom = -1;
        int pre = 0;

        int i = 0;
        int n = html.length();
        while (i < n) {
            char c = html.charAt(i);
            if (c != '<') {
                int end = html.indexOf('<', i);
                if (end < 0) end = n;
                appendText(html, i, end, pre > 0, out);
                i = end;
                continue;
            }
            Tag tag = parseTag(html, i);
            if (tag == null) {
                out.append('<');
                i++;
                continue;
            }
            i = tag.end();

            switch (tag.name()) {
                case "br" -> out.append('\n');
                case "hr" -> out.append("\n---\n");
                case "img" -> {
                    String alt = tag.attrs().get("alt");
                    if (alt != null && !alt.isBlank()) out.append('[').append(decodeEntities(alt)).append(']');
                }
                case "pre" -> {
                    if (tag.closing()) {
                        pre = Math.max(0, pre - 1);
                        out.append(PARAGRAPH);
                    } else {
                        pre++;
                    }
                }
                case "blockquote" -> {
                    if (tag.closing()) {
                        Integer from = quotes.poll();
                        if (from != null) prefixLines(out, from, "> ", "> ");
                        out.append(PARAGRAPH);
                    } else {
                        newline(out);
                        quotes.push(out.length());
                    }
                }
                case "ul", "ol" -> {
                    if (tag.closing()) {
                        lists.poll();
                        out.append(PARAGRAPH);
                    } else {
                        newline(out);
                        lists.push(new int[]{tag.name().equals("ol") ? 1 : 0, 1});
                    }
                }
                case "li" -> {
                    if (tag.closing()) {
                        Integer from = items.poll();
                        if (from != null) {
                            int[] list = lists.peek();
                            String marker = list != null && list[0] == 1 ? (list[1]++) + ". " : "- ";
                            prefixLines(out, from, marker, "  ");
                        }
                        out.append('\n');
                    } else {
                        newline(out);
                        items.push(out.length());
                    }
                }
                case "a" -> {
                    if (tag.closing()) {
                        if (href != null && anchorFrom >= 0 && anchorFrom <= out.length()) {
                            String shown = out.substring(anchorFrom).trim();
                            if (!sameLink(shown, href)) out.append(" <").append(href).append('>');
                        }
                        href = null;
                        anchorFrom = -1;
                    } else {
                        String raw = decodeEntities(tag.attrs().getOrDefault("href", ""));
                        href = raw.isBlank() ? null : raw;
                        anchorFrom = out.length();
                    }
                }
                case "p", "div", "h1", "h2", "h3", "h4", "table" -> {
                    if (tag.closing()) out.append(PARAGRAPH);
                    else newline(out);
                }
                case "tr", "thead", "tbody", "tfoot" -> {
                    // A table row ends a line and not a paragraph, so the rows of a
                    // schedule stay together instead of arriving double spaced.
                    if (tag.closing()) out.append('\n');
                    else newline(out);
                }
                case "td", "th" -> {
                    if (tag.closing()) out.append('\t');
                }
                default -> {
                    // strong, em, u, s and code carry no plain text meaning of their own.
                }
            }
        }
        return tidy(out.toString());
    }

    /**
     * What ends a block. Two newlines rather than one, because a paragraph break in a
     * letter is a blank line and a reader who gets one newline sees a wall of text
     * where the writer laid out an argument. tidy() collapses any run of these back to
     * a single blank line, so nesting cannot open a gap.
     */
    private static final String PARAGRAPH = "\n\n";

    /** True when printing the address a second time would only make the line longer. */
    private static boolean sameLink(String shown, String href) {
        if (shown.isEmpty()) return false;
        String a = shown.toLowerCase(Locale.ROOT);
        String b = href.toLowerCase(Locale.ROOT);
        if (a.equals(b)) return true;
        if (b.startsWith("mailto:") && a.equals(b.substring(7))) return true;
        return b.equals(a + "/") || a.equals(b + "/");
    }

    /** A block boundary only earns a newline when there is already something to end. */
    private static void newline(StringBuilder out) {
        if (out.isEmpty()) return;
        if (out.charAt(out.length() - 1) != '\n') out.append('\n');
    }

    private static void prefixLines(StringBuilder out, int from, String first, String rest) {
        if (from >= out.length()) return;
        String block = out.substring(from);
        out.setLength(from);
        String[] lines = block.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            if (i > 0 && lines[i].isEmpty()) continue;
            out.append(i == 0 ? first : rest).append(lines[i]);
        }
    }

    private static void appendText(String s, int from, int to, boolean verbatim, StringBuilder out) {
        String decoded = decodeEntities(s.substring(from, to));
        if (verbatim) {
            out.append(decoded);
            return;
        }
        // Markup treats every run of whitespace as one space, so text that was
        // indented in the source must not arrive indented in the letter.
        boolean space = false;
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ' || c == '\u00a0') {
                space = true;
                continue;
            }
            if (space) {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') out.append(' ');
                space = false;
            }
            out.append(c);
        }
        if (space && !out.isEmpty() && out.charAt(out.length() - 1) != '\n') out.append(' ');
    }

    private static String tidy(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int blanks = 0;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.stripTrailing();
            if (trimmed.isEmpty()) {
                blanks++;
                if (blanks > 1) continue;
            } else {
                blanks = 0;
            }
            out.append(trimmed).append('\n');
        }
        return out.toString().strip();
    }

    // ------------------------------------------------------------------
    // Tokeniser
    // ------------------------------------------------------------------

    private record Tag(String name, boolean closing, boolean selfClosing,
                       Map<String, String> attrs, int end) {
    }

    /**
     * Returns null when the "&lt;" does not actually start a tag.
     *
     * A quoted attribute value is read the way a browser reads it, which means a
     * "&gt;" inside the quotes does not end the tag. Getting that wrong is how a
     * filter ends up seeing a different document from the one the recipient's client
     * parses, and the gap between those two readings is where every mutation attack
     * lives.
     */
    private static Tag parseTag(String s, int start) {
        int n = s.length();
        int j = start + 1;
        boolean closing = false;
        if (j < n && s.charAt(j) == '/') {
            closing = true;
            j++;
        }
        int nameStart = j;
        while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == ':' || s.charAt(j) == '-')) {
            j++;
        }
        if (j == nameStart) return null;
        String name = s.substring(nameStart, j).toLowerCase(Locale.ROOT);

        Map<String, String> attrs = new LinkedHashMap<>();
        boolean selfClosing = false;
        while (j < n) {
            char c = s.charAt(j);
            if (Character.isWhitespace(c)) {
                j++;
                continue;
            }
            if (c == '/') {
                selfClosing = true;
                j++;
                continue;
            }
            if (c == '>') {
                j++;
                break;
            }
            int an = j;
            while (j < n && s.charAt(j) != '=' && s.charAt(j) != '>' && s.charAt(j) != '/'
                    && !Character.isWhitespace(s.charAt(j))) {
                j++;
            }
            if (j == an) {
                j++;
                continue;
            }
            String key = s.substring(an, j).toLowerCase(Locale.ROOT);
            String value = "";
            while (j < n && Character.isWhitespace(s.charAt(j))) j++;
            if (j < n && s.charAt(j) == '=') {
                j++;
                while (j < n && Character.isWhitespace(s.charAt(j))) j++;
                if (j < n && (s.charAt(j) == '"' || s.charAt(j) == '\'')) {
                    char quote = s.charAt(j++);
                    int vs = j;
                    while (j < n && s.charAt(j) != quote) j++;
                    value = s.substring(vs, Math.min(j, n));
                    if (j < n) j++;
                } else {
                    int vs = j;
                    while (j < n && !Character.isWhitespace(s.charAt(j)) && s.charAt(j) != '>') j++;
                    value = s.substring(vs, j);
                }
            }
            attrs.putIfAbsent(key, value);
        }
        return new Tag(name, closing, selfClosing, attrs, j);
    }

    /** Skips to just past the matching close, counting nested opens of the same name. */
    private static int skipSubtree(String s, int from, String tag) {
        int depth = 1;
        int i = from;
        int n = s.length();
        while (i < n && depth > 0) {
            int lt = s.indexOf('<', i);
            if (lt < 0) return n;
            Tag t = parseTag(s, lt);
            if (t == null) {
                i = lt + 1;
                continue;
            }
            if (t.name().equals(tag)) {
                if (t.closing()) depth--;
                else if (!t.selfClosing()) depth++;
            }
            i = t.end();
        }
        return i;
    }

    // ------------------------------------------------------------------
    // Entities and escaping
    // ------------------------------------------------------------------

    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", "\u00a0"),
            Map.entry("tab", "\t"), Map.entry("newline", "\n"), Map.entry("colon", ":"),
            Map.entry("sol", "/"), Map.entry("lpar", "("), Map.entry("rpar", ")"),
            Map.entry("hellip", "..."), Map.entry("mdash", "-"), Map.entry("ndash", "-"),
            Map.entry("rsquo", "'"), Map.entry("lsquo", "'"), Map.entry("ldquo", "\""),
            Map.entry("rdquo", "\""), Map.entry("bull", "*"), Map.entry("middot", "*"));

    /**
     * Decoded exactly once, at the single point where a value enters this class.
     *
     * The reason the decode lives here rather than at each check is that a scheme test
     * run before the decode sees "&amp;#106;avascript" and passes it, while one run
     * after a second decode would let a doubly encoded value through the first pass.
     * One decode, and then every check downstream reads the same characters the
     * recipient's client will.
     */
    static String decodeEntities(String s) {
        if (s == null) return "";
        if (s.indexOf('&') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            if (j < n && s.charAt(j) == '#') {
                j++;
                int radix = 10;
                if (j < n && (s.charAt(j) == 'x' || s.charAt(j) == 'X')) {
                    radix = 16;
                    j++;
                }
                int ds = j;
                while (j < n && Character.digit(s.charAt(j), radix) >= 0) j++;
                if (j > ds) {
                    try {
                        int cp = Integer.parseInt(s.substring(ds, j), radix);
                        if (cp > 0 && cp <= 0x10FFFF) out.appendCodePoint(cp);
                        else out.append('?');
                    } catch (NumberFormatException e) {
                        out.append('?');
                    }
                    if (j < n && s.charAt(j) == ';') j++;
                    i = j;
                    continue;
                }
            } else {
                int ns = j;
                while (j < n && Character.isLetterOrDigit(s.charAt(j))) j++;
                String named = NAMED.get(s.substring(ns, j).toLowerCase(Locale.ROOT));
                if (named != null) {
                    out.append(named);
                    if (j < n && s.charAt(j) == ';') j++;
                    i = j;
                    continue;
                }
            }
            out.append('&');
            i++;
        }
        return out.toString();
    }

    private static void escapeText(String s, int from, int to, StringBuilder out) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                // An ampersand that already begins an entity is left alone, because
                // re-escaping it turns the sender's "&amp;" into a visible "&amp;amp;"
                // in the recipient's client and does that again on every draft save.
                case '&' -> out.append(startsEntity(s, i, to) ? "&" : "&amp;");
                default -> out.append(c);
            }
        }
    }

    /** True when the ampersand at "at" is the start of a well formed entity reference. */
    private static boolean startsEntity(String s, int at, int to) {
        int j = at + 1;
        if (j < to && s.charAt(j) == '#') {
            j++;
            int radix = 10;
            if (j < to && (s.charAt(j) == 'x' || s.charAt(j) == 'X')) {
                radix = 16;
                j++;
            }
            int ds = j;
            while (j < to && Character.digit(s.charAt(j), radix) >= 0) j++;
            return j > ds && j < to && s.charAt(j) == ';';
        }
        int ns = j;
        while (j < to && Character.isLetterOrDigit(s.charAt(j))) j++;
        return j > ns && j < to && s.charAt(j) == ';';
    }

    /** Every attribute this class emits is double quoted, so these four are enough. */
    private static void escapeAttr(String s, StringBuilder out) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
    }
}
