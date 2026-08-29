package com.jarurat.mailer.webmail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the HTML a stranger emailed us into HTML that is safe to put on screen.
 *
 * WHY THIS IS LAYERED, AND WHY NO SINGLE LAYER IS TRUSTED
 * -------------------------------------------------------
 * A message body is attacker controlled input that we are contractually obliged to
 * render. Every webmail CVE of the last decade is a sanitiser that was defeated by
 * one clever payload, so the design assumes this file WILL eventually be beaten and
 * makes that survivable. Four independent layers, each of which alone would stop the
 * classic attacks:
 *
 *   1. This sanitiser (server side, this file). An ALLOWLIST parser, not a regex
 *      blocklist. Anything not explicitly permitted is dropped, so the tags the brief
 *      names - script, iframe, object, embed, form, input, select, base, meta refresh -
 *      are gone by construction rather than by remembering to ban them. It is a real
 *      tokeniser, because a regex cannot tell <img alt="a>b" onerror=x> apart from a
 *      closed tag and that difference is exactly where sanitisers get broken.
 *
 *   2. The sandboxed iframe (browser, see mail.js). srcdoc with
 *      sandbox="allow-popups allow-popups-to-escape-sandbox". No allow-scripts means
 *      injected JavaScript cannot execute even if layer 1 let it through. No
 *      allow-same-origin means the frame has an opaque origin, so it cannot read our
 *      cookies, our DOM, or call our API even if script somehow ran. No allow-forms
 *      means a phishing form cannot post. allow-popups plus
 *      allow-popups-to-escape-sandbox exist only so that a legitimate link still opens
 *      in a normal tab instead of a crippled one.
 *
 *   3. A Content-Security-Policy meta injected into the srcdoc document by this class
 *      (see wrapDocument). default-src 'none' means the frame cannot fetch anything we
 *      did not name. img-src is the switch behind the "show images" button.
 *
 *   4. The application CSP already set in SecurityConfig, which a srcdoc frame
 *      inherits from its parent. Independent of everything above.
 *
 * REMOTE IMAGES are blocked by default because an <img> is a read receipt: it tells
 * the sender the message was opened, from which IP, at what time. Unblocking is a
 * fresh authenticated request, not a client side flag, so the browser cannot turn
 * tracking back on by itself.
 */
public final class MailHtmlSanitizer {

    private MailHtmlSanitizer() {
    }

    /** Sanitised markup plus how many remote images were withheld, for the banner. */
    public record Result(String html, int blockedImages) {
    }

    /** Bodies larger than this are truncated. A 5 MB body is an attack, not a newsletter. */
    private static final int MAX_INPUT = 2_000_000;
    private static final int MAX_CSS = 200_000;
    private static final int MAX_DEPTH = 64;

    /** A transparent 1x1 gif, so a withheld image is still a real box we can outline. */
    private static final String PIXEL =
            "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";

    private static final Set<String> ALLOWED = Set.of(
            "a", "abbr", "address", "article", "aside", "b", "bdi", "bdo", "big", "blockquote",
            "br", "caption", "center", "cite", "code", "col", "colgroup", "dd", "del", "dfn",
            "div", "dl", "dt", "em", "figcaption", "figure", "font", "footer", "h1", "h2", "h3",
            "h4", "h5", "h6", "header", "hr", "i", "img", "ins", "kbd", "li", "main", "mark",
            "nav", "ol", "p", "pre", "q", "s", "samp", "section", "small", "span", "strike",
            "strong", "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "time",
            "tr", "tt", "u", "ul", "var", "wbr");

    /** Allowed elements that never have a closing tag. */
    private static final Set<String> VOID = Set.of("br", "col", "hr", "img", "wbr");

    /**
     * Content is raw text to the HTML parser, so it must be consumed wholesale rather
     * than parsed. Leaving it to the normal path would dump JavaScript source into the
     * reader as visible text, and worse, would let "</script><img onerror>" reopen markup.
     */
    private static final Set<String> RAW_TEXT = Set.of("script", "textarea", "title");

    /**
     * Dropped along with everything inside them. These carry no readable content, and
     * keeping their children would just resurrect the controls we are removing.
     */
    private static final Set<String> DROP_SUBTREE = Set.of(
            "iframe", "object", "form", "select", "option", "optgroup", "button", "applet",
            "svg", "math", "frameset", "frame", "noscript", "template", "audio", "video",
            "canvas", "map", "datalist", "portal");

    /**
     * Void elements that are simply deleted. base rewrites every relative URL in the
     * document, meta does refresh redirects, link pulls remote stylesheets, and input
     * paints a login box that is not ours.
     */
    private static final Set<String> DROP_TAG = Set.of(
            "base", "meta", "link", "input", "embed", "param", "source", "track", "keygen",
            "isindex", "basefont");

    private static final Set<String> GLOBAL_ATTRS = Set.of(
            "title", "dir", "lang", "align", "valign", "class", "style");

    private static final Map<String, Set<String>> EXTRA_ATTRS = Map.ofEntries(
            Map.entry("a", Set.of("href")),
            Map.entry("img", Set.of("src", "alt", "width", "height", "border", "hspace", "vspace")),
            Map.entry("table", Set.of("width", "height", "border", "cellpadding", "cellspacing", "bgcolor", "background")),
            Map.entry("td", Set.of("width", "height", "colspan", "rowspan", "bgcolor", "background", "nowrap")),
            Map.entry("th", Set.of("width", "height", "colspan", "rowspan", "bgcolor", "background", "nowrap")),
            Map.entry("tr", Set.of("height", "bgcolor", "background")),
            Map.entry("tbody", Set.of("bgcolor")),
            Map.entry("thead", Set.of("bgcolor")),
            Map.entry("tfoot", Set.of("bgcolor")),
            Map.entry("col", Set.of("span", "width")),
            Map.entry("colgroup", Set.of("span", "width")),
            Map.entry("font", Set.of("color", "face", "size")),
            Map.entry("ol", Set.of("type", "start")),
            Map.entry("ul", Set.of("type")),
            Map.entry("li", Set.of("type", "value")),
            Map.entry("hr", Set.of("size", "width", "noshade")));

    private static final Set<String> SAFE_SCHEMES = Set.of("http", "https", "mailto", "tel");

    /** Checked after CSS comments and escapes are removed, so obfuscation does not help. */
    private static final String[] CSS_POISON = {
            "expression(", "javascript:", "vbscript:", "-moz-binding", "behavior:", "@import",
            "@charset", "@namespace"};

    /**
     * The one script allowed to run inside a message frame, and the only reason the
     * sandbox now carries allow-scripts at all.
     *
     * The frame cannot report its own height without scripting, so the reader had to
     * give it a fixed height and let it scroll internally. That put two scrollers on
     * one pane, one for the header and one for the letter, and a touch gesture does
     * not chain from the outer to the inner: the message stopped moving partway
     * through and the rest of it was unreachable without lifting a finger and starting
     * again. Reported as the mail being cut off, which is exactly how it reads.
     *
     * With the height known the frame is sized to its content, the wrapper stops
     * scrolling, and the pane has one scroller from the subject to the last line.
     *
     * What this does NOT give away. The sandbox still has no allow-same-origin, so the
     * frame runs in an opaque origin: it cannot read our cookies, reach our DOM, or
     * call our API even if the sanitiser above is one day defeated. And script-src is
     * pinned to the sha256 of exactly this source, so a script the tokeniser somehow
     * missed does not match the hash and does not execute. The hash is over the string
     * byte for byte; changing so much as a space here without recomputing it silently
     * turns the sizing off, which is a broken reader rather than a security hole, but
     * it is worth knowing before editing.
     */
    private static final String HEIGHT_REPORTER =
            "(function(){function r(){parent.postMessage({jmHeight:Math.max(document.documentElement.scrollHeight,"
            + "document.body?document.body.scrollHeight:0)},'*');}if(document.readyState==='loading')"
            + "{document.addEventListener('DOMContentLoaded',r);}else{r();}window.addEventListener('load',r);"
            + "window.addEventListener('resize',r);setTimeout(r,300);setTimeout(r,1200);})();";

    private static final String HEIGHT_REPORTER_HASH = "sha256-aj11KTDM/4IGGcIiAgMSau/PKTR40g0JkpK4dY8iNe8=";

    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern CSS_URL = Pattern.compile("url\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINKIFY = Pattern.compile("https?://[^\\s<>\"')\\]]+");

    // ------------------------------------------------------------------ public API

    /**
     * The whole job in one call: pick the best body the message offers, clean it, and
     * wrap it in a standalone document ready to hand to an iframe's srcdoc property.
     */
    public static Result toReaderDocument(String html, String text, boolean allowRemoteImages, String theme) {
        // "original" is the escape hatch: render the message exactly as the sender
        // built it, on white, with no colour re-tuning at all. Everything else reads on
        // the dark shell, because a white slab inside a dark application is jarring and
        // the lifting below is what makes that safe. See liftForDarkGround.
        boolean showOriginal = "original".equalsIgnoreCase(theme) || "light".equalsIgnoreCase(theme);
        boolean dark = !showOriginal;

        Result body;
        if (html != null && !html.isBlank()) {
            body = sanitize(html, allowRemoteImages, dark);
        } else {
            body = new Result(plainTextToHtml(text), 0);
        }
        return new Result(wrapDocument(body.html(), allowRemoteImages, dark ? "dark" : "light"),
                body.blockedImages());
    }

    /** Sanitises a fragment. The result is a fragment, not a document. */
    public static Result sanitize(String rawHtml, boolean allowRemoteImages) {
        return sanitize(rawHtml, allowRemoteImages, false);
    }

    /** @param darkGround see liftForDarkGround. */
    public static Result sanitize(String rawHtml, boolean allowRemoteImages, boolean darkGround) {
        if (rawHtml == null || rawHtml.isBlank()) return new Result("", 0);

        String src = rawHtml.length() > MAX_INPUT ? rawHtml.substring(0, MAX_INPUT) : rawHtml;
        StringBuilder out = new StringBuilder(Math.min(src.length() + 512, MAX_INPUT));
        Deque<String> open = new ArrayDeque<>();
        // Runs in lockstep with open, recording which of those elements painted a light
        // ground, so a child can be asked what it is actually sitting on.
        Deque<Boolean> lightGround = new ArrayDeque<>();
        int lightDepth = 0;
        int[] blocked = {0};

        int i = 0;
        int n = src.length();
        while (i < n) {
            if (src.charAt(i) != '<') {
                int lt = src.indexOf('<', i);
                int end = lt < 0 ? n : lt;
                escapeText(src, i, end, out);
                i = end;
                continue;
            }

            // Comments go entirely. Conditional comments are markup in disguise and
            // Outlook mail is full of them.
            if (src.startsWith("<!--", i)) {
                int e = src.indexOf("-->", i + 4);
                i = e < 0 ? n : e + 3;
                continue;
            }
            if (src.startsWith("<!", i) || src.startsWith("<?", i)) {
                int e = src.indexOf('>', i);
                i = e < 0 ? n : e + 1;
                continue;
            }

            Tag tag = parseTag(src, i);
            if (tag == null) {
                // A bare "<" in prose. Escape it and move on.
                out.append("&lt;");
                i++;
                continue;
            }
            i = tag.end;
            String name = tag.name;

            if (tag.closing) {
                if (VOID.contains(name) || !ALLOWED.contains(name) || !open.contains(name)) continue;
                String popped;
                do {
                    popped = open.pop();
                    // Popped in step with open, so the inherited-ground depth unwinds
                    // exactly when the element that painted it closes.
                    if (!lightGround.isEmpty() && Boolean.TRUE.equals(lightGround.pop())) lightDepth--;
                    out.append("</").append(popped).append('>');
                } while (!popped.equals(name));
                continue;
            }

            if ("style".equals(name)) {
                int[] span = rawTextSpan(src, i, "style");
                String css = sanitizeStylesheet(src.substring(i, span[0]), allowRemoteImages, darkGround);
                if (!css.isEmpty()) out.append("<style>").append(css).append("</style>");
                i = span[1];
                continue;
            }
            if (RAW_TEXT.contains(name)) {
                i = rawTextSpan(src, i, name)[1];
                continue;
            }
            if (DROP_SUBTREE.contains(name)) {
                if (!tag.selfClosing) i = skipSubtree(src, i, name);
                continue;
            }
            if (DROP_TAG.contains(name)) continue;

            // Unknown but harmless wrappers (body, o:p, custom elements) are unwrapped:
            // the tag goes, the text inside stays.
            if (!ALLOWED.contains(name)) continue;

            if (open.size() >= MAX_DEPTH) continue;

            // Whether this element paints a light ground that its CHILDREN will sit on.
            //
            // Checking only the element's own rule was not enough and produced exactly
            // the failure it was meant to prevent. The "this message came from outside
            // your organisation" banner that many senders prepend is a table carrying
            // a light grey background wrapping a span carrying colour:#000 and no
            // background of its own. The span was therefore treated as needing a lift,
            // its near-black was turned near-white, and it landed white on light grey:
            // completely invisible, which is what was reported.
            //
            // So the ground is inherited the way it actually is on screen. While the
            // walk is inside anything that painted itself light, nothing below it is
            // lifted, because down there the sender's dark text is correct.
            boolean paintsLight = darkGround && paintsLightGround(tag.attrs);
            boolean liftHere = darkGround && lightDepth == 0;

            out.append('<').append(name);
            writeAttributes(name, tag.attrs, allowRemoteImages, out, blocked, liftHere);
            out.append('>');
            if (!VOID.contains(name)) {
                if (tag.selfClosing) out.append("</").append(name).append('>');
                else {
                    open.push(name);
                    lightGround.push(paintsLight);
                    if (paintsLight) lightDepth++;
                }
            }
        }

        while (!open.isEmpty()) out.append("</").append(open.pop()).append('>');
        return new Result(out.toString(), blocked[0]);
    }

    /** Plain text bodies still need escaping, and still deserve clickable links. */
    public static String plainTextToHtml(String text) {
        if (text == null || text.isBlank()) return "<p class=\"jc-empty\">This message has no readable body.</p>";
        String src = text.length() > MAX_INPUT ? text.substring(0, MAX_INPUT) : text;

        StringBuilder out = new StringBuilder(src.length() + 256);
        out.append("<pre class=\"jc-plain\">");
        Matcher m = LINKIFY.matcher(src);
        int at = 0;
        while (m.find()) {
            escapeText(src, at, m.start(), out);
            String url = trimTrailingPunctuation(m.group());
            String safe = cleanUrl(url);
            if (safe == null) {
                escapeAttr(url, out);
            } else {
                out.append("<a href=\"");
                escapeAttr(safe, out);
                out.append("\" target=\"_blank\" rel=\"noopener noreferrer nofollow\">");
                escapeText(url, 0, url.length(), out);
                out.append("</a>");
            }
            at = m.start() + url.length();
        }
        escapeText(src, at, src.length(), out);
        out.append("</pre>");
        return out.toString();
    }

    // ------------------------------------------------------------------ tokeniser

    private record Tag(String name, boolean closing, boolean selfClosing,
                       Map<String, String> attrs, int end) {
    }

    /** Returns null when the "<" does not actually start a tag. */
    private static Tag parseTag(String s, int start) {
        int n = s.length();
        int j = start + 1;
        boolean closing = false;
        if (j < n && s.charAt(j) == '/') {
            closing = true;
            j++;
        }
        int nameStart = j;
        if (j >= n || !Character.isLetter(s.charAt(j))) return null;
        while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == ':' || s.charAt(j) == '-')) j++;
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
                return new Tag(name, closing, selfClosing, attrs, j);
            }
            selfClosing = false;

            int an = j;
            while (j < n) {
                char a = s.charAt(j);
                if (Character.isWhitespace(a) || a == '=' || a == '>' || a == '/') break;
                j++;
            }
            if (j == an) {
                j++;   // a stray "=" or quote: skip it rather than spin
                continue;
            }
            String attrName = s.substring(an, j).toLowerCase(Locale.ROOT);

            while (j < n && Character.isWhitespace(s.charAt(j))) j++;
            String value = "";
            if (j < n && s.charAt(j) == '=') {
                j++;
                while (j < n && Character.isWhitespace(s.charAt(j))) j++;
                if (j < n && (s.charAt(j) == '"' || s.charAt(j) == '\'')) {
                    char quote = s.charAt(j++);
                    int vs = j;
                    // A quoted value swallows ">" exactly as the browser's parser does.
                    // Getting this wrong is how regex sanitisers get bypassed.
                    while (j < n && s.charAt(j) != quote) j++;
                    value = s.substring(vs, Math.min(j, n));
                    if (j < n) j++;
                } else {
                    int vs = j;
                    while (j < n && !Character.isWhitespace(s.charAt(j)) && s.charAt(j) != '>') j++;
                    value = s.substring(vs, j);
                }
            }
            // Decoded once, here, because the browser decodes attribute values too.
            // Doing it at the single point of entry is what stops "java&#115;cript:"
            // from looking harmless to every check downstream.
            attrs.putIfAbsent(attrName, decodeEntities(value));
        }
        // Ran off the end without a ">". Treat what we parsed as the tag.
        return new Tag(name, closing, selfClosing, attrs, n);
    }

    /** {content end, index after the closing tag} for a raw text element. */
    private static int[] rawTextSpan(String s, int from, String tag) {
        String needle = "</" + tag;
        int i = from;
        while (i < s.length()) {
            int k = indexOfIgnoreCase(s, needle, i);
            if (k < 0) return new int[]{s.length(), s.length()};
            int after = k + needle.length();
            if (after >= s.length()) return new int[]{k, s.length()};
            char c = s.charAt(after);
            if (c == '>' || c == '/' || Character.isWhitespace(c)) {
                int gt = s.indexOf('>', after);
                return new int[]{k, gt < 0 ? s.length() : gt + 1};
            }
            i = k + 1;
        }
        return new int[]{s.length(), s.length()};
    }

    /**
     * Skips a whole element including nested copies of itself. A "&gt;" inside a quoted
     * attribute of some inner tag can make this stop early, which is harmless: whatever
     * is left over goes back through the main loop and is sanitised there anyway.
     */
    private static int skipSubtree(String s, int from, String tag) {
        int depth = 1;
        int i = from;
        int n = s.length();
        while (i < n && depth > 0) {
            int lt = s.indexOf('<', i);
            if (lt < 0) return n;
            boolean closing = lt + 1 < n && s.charAt(lt + 1) == '/';
            int nameStart = closing ? lt + 2 : lt + 1;
            int j = nameStart;
            while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == ':' || s.charAt(j) == '-')) j++;
            if (!s.regionMatches(true, nameStart, tag, 0, tag.length()) || j - nameStart != tag.length()) {
                i = lt + 1;
                continue;
            }
            int gt = s.indexOf('>', j);
            int end = gt < 0 ? n : gt + 1;
            if (closing) {
                depth--;
                i = end;
            } else {
                if (gt > 0 && s.charAt(gt - 1) == '/') i = end;
                else {
                    depth++;
                    i = end;
                }
            }
        }
        return i;
    }

    // ------------------------------------------------------------------ attributes

    private static void writeAttributes(String tag, Map<String, String> attrs,
                                        boolean allowRemoteImages, StringBuilder out, int[] blocked,
                                        boolean darkGround) {
        Set<String> extra = EXTRA_ATTRS.getOrDefault(tag, Set.of());
        boolean anchorHasHref = false;
        boolean imageWithheld = false;

        for (Map.Entry<String, String> e : attrs.entrySet()) {
            String key = e.getKey();
            String raw = e.getValue();

            // Belt and braces. The allowlist below already excludes these, but an event
            // handler slipping through is the one mistake worth failing twice on.
            if (key.startsWith("on") || key.equals("srcdoc") || key.equals("formaction")
                    || key.equals("xlink:href") || key.equals("http-equiv") || key.equals("action")) {
                continue;
            }
            if (!GLOBAL_ATTRS.contains(key) && !extra.contains(key)) continue;

            // The presentational colour attributes predate CSS and are still what a
            // great many newsletters use, so they need the same lift as a style rule or
            // half a message reads and the other half does not. An element carrying its
            // own bgcolor has chosen a pairing and is left alone, exactly as in
            // sanitizeDeclarations.
            if (darkGround && "color".equals(key) && !attrs.containsKey("bgcolor")) {
                raw = liftForDarkGround("color:" + raw).substring("color:".length());
            }

            switch (key) {
                case "style" -> {
                    String css = sanitizeDeclarations(raw, allowRemoteImages, darkGround);
                    if (!css.isEmpty()) {
                        out.append(" style=\"");
                        escapeAttr(css, out);
                        out.append('"');
                    }
                }
                case "href" -> {
                    String url = cleanUrl(raw);
                    if (url != null) {
                        out.append(" href=\"");
                        escapeAttr(url, out);
                        out.append('"');
                        anchorHasHref = true;
                    }
                }
                case "src" -> {
                    String url = cleanUrl(raw);
                    if (url == null) {
                        // cid: and relative sources cannot be served, so they render as
                        // a withheld image rather than a broken one.
                        imageWithheld = true;
                    } else if (isInlineData(url)) {
                        out.append(" src=\"");
                        escapeAttr(url, out);
                        out.append('"');
                    } else if (allowRemoteImages) {
                        out.append(" src=\"");
                        escapeAttr(url, out);
                        out.append('"');
                    } else {
                        blocked[0]++;
                        imageWithheld = true;
                    }
                }
                case "background" -> {
                    String url = cleanUrl(raw);
                    if (url != null && !isInlineData(url)) {
                        if (allowRemoteImages) {
                            out.append(" background=\"");
                            escapeAttr(url, out);
                            out.append('"');
                        } else {
                            blocked[0]++;
                        }
                    }
                }
                default -> {
                    out.append(' ').append(key).append("=\"");
                    escapeAttr(raw, out);
                    out.append('"');
                }
            }
        }

        if ("a".equals(tag) && anchorHasHref) {
            // noopener because the popup escapes the sandbox; noreferrer so the target
            // never learns which mailbox opened it.
            out.append(" target=\"_blank\" rel=\"noopener noreferrer nofollow\"");
        }
        if ("img".equals(tag) && imageWithheld) {
            out.append(" src=\"").append(PIXEL).append("\" class=\"jc-blocked-img\"");
        }
    }

    /**
     * Raster image data URLs only.
     *
     * SVG is deliberately excluded even though it is an image type. A sanitiser
     * cannot see inside a base64 payload, so data:image/svg+xml is a sealed
     * envelope that can carry script, foreignObject or feImage past every check
     * above it. The sandboxed frame would stop the script running today, but this
     * is the layer that must not depend on that, and an allowlist of formats that
     * cannot contain markup costs nothing.
     */
    private static boolean isInlineData(String url) {
        if (!url.regionMatches(true, 0, "data:image/", 0, 11)) return false;
        String rest = url.substring(11).toLowerCase(Locale.ROOT);
        for (String type : RASTER_DATA_TYPES) {
            if (rest.startsWith(type)) return true;
        }
        return false;
    }

    /** Formats with no markup layer, so no script can hide inside one. */
    private static final List<String> RASTER_DATA_TYPES =
            List.of("png", "jpeg", "jpg", "gif", "webp", "bmp", "avif");

    /**
     * Returns a URL that is safe to put in an attribute, or null to drop it. The value
     * is expected to be entity decoded already (parseTag does it). Whitespace and
     * control characters are ignored while identifying the scheme, because the browser
     * ignores them too and "java\tscript:" is a real payload.
     */
    static String cleanUrl(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;

        StringBuilder probe = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c > 0x20 && c != 0x7f) probe.append(Character.toLowerCase(c));
        }
        String p = probe.toString();

        int colon = p.indexOf(':');
        if (colon <= 0) return null;                       // relative URLs: see below
        int slash = p.indexOf('/');
        int hash = p.indexOf('#');
        int query = p.indexOf('?');
        boolean schemeFirst = (slash < 0 || colon < slash) && (hash < 0 || colon < hash)
                && (query < 0 || colon < query);
        // Not a scheme means a relative or fragment URL. Those resolve against OUR
        // origin inside the frame, so a link could impersonate a console page. Dropped.
        if (!schemeFirst) return null;

        String scheme = p.substring(0, colon);
        if (SAFE_SCHEMES.contains(scheme)) return v;
        // Same raster-only rule as isInlineData: svg+xml is an image type that can
        // carry script, and nothing here can inspect a base64 payload.
        if (scheme.equals("data") && isInlineData(p)) return v;
        return null;
    }

    private static String decodeEntities(String s) {
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
                while (j < n && j - ds < 8 && Character.digit(s.charAt(j), radix) >= 0) j++;
                if (j > ds) {
                    try {
                        int cp = Integer.parseInt(s.substring(ds, j), radix);
                        if (cp > 0 && cp <= 0x10FFFF) out.appendCodePoint(cp);
                    } catch (NumberFormatException ignored) {
                        // malformed entity, drop it
                    }
                    if (j < n && s.charAt(j) == ';') j++;
                    i = j;
                    continue;
                }
            } else {
                int ns = j;
                while (j < n && j - ns < 12 && Character.isLetter(s.charAt(j))) j++;
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

    /** Only the entities that matter for scheme smuggling, plus the obvious ones. */
    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "),
            Map.entry("colon", ":"), Map.entry("tab", "\t"), Map.entry("newline", "\n"),
            Map.entry("sol", "/"), Map.entry("lpar", "("), Map.entry("rpar", ")"),
            Map.entry("semi", ";"));

    // ------------------------------------------------------------------ css

    /** Sanitises the contents of a style attribute, declaration by declaration. */
    static String sanitizeDeclarations(String raw, boolean allowRemoteImages) {
        return sanitizeDeclarations(raw, allowRemoteImages, false);
    }

    /**
     * @param darkGround the reader is painting this message on the dark shell, so a
     *                   colour the sender chose against white has to be re-tuned or it
     *                   lands as near-black on near-black.
     */
    static String sanitizeDeclarations(String raw, boolean allowRemoteImages, boolean darkGround) {
        if (raw == null || raw.isBlank()) return "";
        String css = prepareCss(raw);
        if (css.isEmpty()) return "";

        List<String> decls = splitDeclarations(css);

        // Whether this same rule paints its own ground. If it does, the sender has
        // chosen a pairing and it is left completely alone: a branded header block
        // with white text on its own dark panel is correct as written, and "fixing"
        // its text colour is what breaks those messages.
        boolean bringsOwnGround = false;
        for (String decl : decls) {
            String p = propertyOf(decl);
            if (("background".equals(p) || "background-color".equals(p)) && !isTransparent(decl)) {
                bringsOwnGround = true;
                break;
            }
        }

        StringBuilder out = new StringBuilder(css.length());
        for (String decl : decls) {
            String d = decl.trim();
            if (d.isEmpty()) continue;
            int colon = d.indexOf(':');
            if (colon <= 0) continue;
            String prop = d.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!prop.matches("[a-z-]{2,40}")) continue;
            if (isPoisonedCss(d)) continue;
            String cleaned = filterCssUrls(d, allowRemoteImages);
            if (cleaned == null) continue;
            if (darkGround && !bringsOwnGround && ("color".equals(prop) || "border-color".equals(prop))) {
                cleaned = liftForDarkGround(cleaned);
            }
            if ("font-size".equals(prop)) cleaned = atLeastReadable(cleaned);
            if (out.length() > 0) out.append(';');
            out.append(cleaned);
        }
        return out.toString();
    }

    /**
     * Does this element paint a ground light enough that dark text on it is correct.
     *
     * Reads both the modern and the ancient way of saying it, because the messages this
     * matters for are exactly the ones written in table markup from 2004: a style
     * attribute carrying background or background-color, and the bgcolor attribute.
     * A dark ground the sender chose is not interesting here, since text on it already
     * needs to be light and the lift would leave it alone anyway.
     */
    private static boolean paintsLightGround(Map<String, String> attrs) {
        String candidate = null;
        String bg = attrs.get("bgcolor");
        if (bg != null && !bg.isBlank()) candidate = bg;

        String style = attrs.get("style");
        if (style != null) {
            for (String decl : splitDeclarations(prepareCss(style))) {
                String p = propertyOf(decl);
                if (("background".equals(p) || "background-color".equals(p)) && !isTransparent(decl)) {
                    candidate = decl.substring(decl.indexOf(':') + 1);
                    break;
                }
            }
        }
        if (candidate == null) return false;

        int[] rgb = firstColour(candidate);
        if (rgb == null) return false;
        double lum = 0.2126 * srgb(rgb[0]) + 0.7152 * srgb(rgb[1]) + 0.0722 * srgb(rgb[2]);
        // Anything above a mid grey is a ground the sender expects dark text on. The
        // threshold is deliberately generous: treating a ground as light when it is
        // borderline leaves the sender's own colours alone, which is the safe mistake.
        return lum > 0.18;
    }

    /** The first hex or rgb() colour in a value, or null when there is none. */
    private static int[] firstColour(String value) {
        Matcher hex = CSS_HEX.matcher(value);
        if (hex.find()) {
            String h = hex.group(1);
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            return new int[]{Integer.parseInt(h.substring(0, 2), 16),
                             Integer.parseInt(h.substring(2, 4), 16),
                             Integer.parseInt(h.substring(4, 6), 16)};
        }
        Matcher rgb = CSS_RGB.matcher(value);
        if (rgb.find()) {
            return new int[]{clamp255(Integer.parseInt(rgb.group(1))),
                             clamp255(Integer.parseInt(rgb.group(2))),
                             clamp255(Integer.parseInt(rgb.group(3)))};
        }
        // A bare colour keyword. Only the handful that actually turn up as a banner
        // ground are worth knowing, and everything else falls through as "not light",
        // which leaves the lift on and is the behaviour without this method at all.
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "white", "#fff", "ivory", "snow", "whitesmoke" -> new int[]{255, 255, 255};
            case "lightgrey", "lightgray", "gainsboro" -> new int[]{211, 211, 211};
            case "silver" -> new int[]{192, 192, 192};
            case "beige", "cornsilk", "lightyellow" -> new int[]{245, 245, 220};
            default -> null;
        };
    }

    private static String propertyOf(String decl) {
        int colon = decl.indexOf(':');
        return colon <= 0 ? "" : decl.substring(0, colon).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isTransparent(String decl) {
        String v = decl.substring(decl.indexOf(':') + 1).trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() || v.contains("transparent") || v.startsWith("none")
                || v.matches(".*rgba\\([^)]*,\\s*0(\\.0+)?\\s*\\).*");
    }

    private static final Pattern CSS_HEX = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})\\b");
    private static final Pattern CSS_RGB =
            Pattern.compile("rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*(?:,[^)]*)?\\)");

    /**
     * Re-tunes one colour so it stays legible on the dark reading surface.
     *
     * The sender picked this colour against white, because that is what mail clients
     * have always shown, and almost every HTML mail sets a dark text colour and no
     * background at all. Rendering the message on white was the obvious way out and it
     * is what Gmail does, but a white slab inside a dark application is jarring and it
     * is not what was asked for here.
     *
     * So the hue the sender chose is kept and only its lightness is flipped, which is
     * what Outlook's dark mode does. A near-black body colour becomes a near-white one,
     * a mid grey becomes a lighter grey, and a brand colour stays recognisably itself.
     * Anything already light enough to read on the dark ground is left untouched, so a
     * sender who wrote for dark is not inverted back into being unreadable.
     */
    static String liftForDarkGround(String decl) {
        Matcher hex = CSS_HEX.matcher(decl);
        StringBuilder sb = new StringBuilder();
        while (hex.find()) {
            String h = hex.group(1);
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            hex.appendReplacement(sb, Matcher.quoteReplacement(lift(r, g, b)));
        }
        hex.appendTail(sb);

        Matcher rgb = CSS_RGB.matcher(sb.toString());
        StringBuilder sb2 = new StringBuilder();
        while (rgb.find()) {
            int r = clamp255(Integer.parseInt(rgb.group(1)));
            int g = clamp255(Integer.parseInt(rgb.group(2)));
            int b = clamp255(Integer.parseInt(rgb.group(3)));
            rgb.appendReplacement(sb2, Matcher.quoteReplacement(lift(r, g, b)));
        }
        rgb.appendTail(sb2);
        return sb2.toString();
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /**
     * The lightness flip itself. Relative luminance decides whether a colour needs it,
     * rather than a plain average, because the eye is far more sensitive to green than
     * to blue and an average calls a saturated blue "light" when it reads as dark.
     */
    private static String lift(int r, int g, int b) {
        float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);

        // A NEAR NEUTRAL GREY IS NOT A COLOUR CHOICE, it is the sender reaching for
        // "body text", and it becomes the reader's own body colour.
        //
        // Passing contrast is not the same as looking right, and treating them as the
        // same produced a real complaint. The rule below only fired under 4.5:1, so a
        // sender's #999999 cleared the floor and was left exactly as written. It then
        // sat in the same message as text with no colour at all, which inherits our
        // #ededed. The result was a letter whose actual content was dim grey while the
        // legal boilerplate underneath it, which sets no colour, was bright white: the
        // least important text on screen was the most prominent, and every message
        // looked slightly different from the last depending on which grey its author
        // happened to pick.
        //
        // Saturation is what separates a deliberate colour from a shade of grey. Below
        // this threshold there is no hue worth preserving, so nothing is lost by
        // snapping it, and consistency is gained across every message in the mailbox.
        if (hsb[1] <= 0.12f) return READER_TEXT;

        double lum = (0.2126 * srgb(r) + 0.7152 * srgb(g) + 0.0722 * srgb(b));
        // Contrast against the #202020 reading surface. A COLOUR that already clears the
        // floor is the sender writing for dark, and is left alone.
        double groundLum = 0.0144;
        double ratio = (Math.max(lum, groundLum) + 0.05) / (Math.min(lum, groundLum) + 0.05);
        if (lum > groundLum && ratio >= 4.5) return css(r, g, b);

        // Hue is kept, saturation is eased back so a lifted colour does not glow, and
        // brightness lands in a band that clears the floor without being pure white.
        float brightness = 0.86f - 0.12f * hsb[1];
        float saturation = Math.min(hsb[1], 0.55f);
        java.awt.Color lifted = java.awt.Color.getHSBColor(hsb[0], saturation, brightness);
        return css(lifted.getRed(), lifted.getGreen(), lifted.getBlue());
    }

    /** The reader's own body colour, matching --jct in the dark wrapper. */
    private static final String READER_TEXT = "#ededed";

    /** Below this a message is not small, it is unreadable on a phone held at arm's length. */
    private static final double MIN_FONT_PX = 14.0;

    private static final Pattern FONT_SIZE_VALUE =
            Pattern.compile("(-?\\d*\\.?\\d+)\\s*(px|pt|em|rem|%)", Pattern.CASE_INSENSITIVE);

    /**
     * Raises a font size the sender chose to something a person can actually read.
     *
     * Mail is full of small type and almost none of it is a design decision. A quoted
     * reply chain is emitted at 10 or 11 pixels by most clients, a signature block is
     * routinely 9, and Outlook writes sizes in points against assumptions about a
     * desktop screen from twenty years ago. On a phone that is not small text, it is a
     * grey smear, and it is why one message in this mailbox reads comfortably and the
     * next one, which is a reply to it, does not.
     *
     * Only the floor moves. Anything at or above it is the sender's decision and is
     * left exactly as written, so headings, large type and deliberate emphasis all
     * survive. Relative units are left alone entirely: em, rem and percentage compound
     * against a parent this method cannot see, so "0.9em" may well be larger than the
     * floor already, and rewriting it to a pixel value would flatten a whole nested
     * structure to one size.
     */
    static String atLeastReadable(String decl) {
        int colon = decl.indexOf(':');
        if (colon <= 0) return decl;
        String value = decl.substring(colon + 1);

        Matcher m = FONT_SIZE_VALUE.matcher(value);
        if (!m.find()) return decl;

        String unit = m.group(2).toLowerCase(Locale.ROOT);
        if (!"px".equals(unit) && !"pt".equals(unit)) return decl;

        double size;
        try {
            size = Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return decl;
        }
        // A point is four thirds of a CSS pixel, which is the whole reason Outlook's
        // "11pt" and a browser's "11px" are not the same text.
        double px = "pt".equals(unit) ? size * 4.0 / 3.0 : size;
        if (px >= MIN_FONT_PX) return decl;

        return decl.substring(0, colon + 1) + " " + (int) Math.round(MIN_FONT_PX) + "px";
    }

    private static double srgb(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static String css(int r, int g, int b) {
        return String.format("#%02x%02x%02x", r, g, b);
    }

    /**
     * Sanitises a whole stylesheet by REBUILDING it from the rules it recognises,
     * rather than by filtering the text and passing the rest through. That distinction
     * matters: a style element whose content is not CSS at all, which is what an
     * attacker sends when they are trying to smuggle markup through the raw text
     * parsing rules, produces no rules and therefore produces no output. Nothing the
     * sender wrote reaches the page unless it parsed as "selector { declarations }".
     */
    static String sanitizeStylesheet(String raw, boolean allowRemoteImages) {
        return sanitizeStylesheet(raw, allowRemoteImages, false);
    }

    static String sanitizeStylesheet(String raw, boolean allowRemoteImages, boolean darkGround) {
        if (raw == null || raw.isBlank()) return "";
        String css = prepareCss(raw.length() > MAX_CSS ? raw.substring(0, MAX_CSS) : raw);
        if (css.isEmpty() || isPoisonedCss(css)) return "";
        return rebuildRules(css, allowRemoteImages, 0, darkGround);
    }

    private static final Pattern PREFERS_DARK =
            Pattern.compile("prefers-color-scheme\\s*:\\s*dark", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFERS_LIGHT =
            Pattern.compile("prefers-color-scheme\\s*:\\s*light", Pattern.CASE_INSENSITIVE);

    /**
     * How an at-rule's media query relates to the light ground the reader forces.
     *
     * Returns -1 when the block can never apply and must be dropped, 1 when it always
     * applies and should be unwrapped so it fires whatever the reader's machine is set
     * to, and 0 when the colour scheme is not mentioned and the rule is left alone.
     *
     * A query that mentions the scheme alongside other conditions, such as a width, is
     * deliberately left alone: unwrapping it would apply rules at every width, which
     * breaks layout, and that is a worse outcome than a style that quietly does not
     * fire. Legibility is the thing being protected here, and the dark branch, which is
     * the one that makes text vanish, is dropped in every form.
     */
    private static int colourSchemeIntent(String prelude) {
        boolean dark = PREFERS_DARK.matcher(prelude).find();
        boolean light = PREFERS_LIGHT.matcher(prelude).find();
        if (!dark && !light) return 0;
        // "not (prefers-color-scheme: dark)" means light, and the negation flips both.
        boolean negated = prelude.toLowerCase(Locale.ROOT).contains("not ");
        if (dark && light) return 0;                    // both branches in one query, leave it
        boolean selectsDark = negated != dark;          // XOR
        if (selectsDark) return -1;
        // Only unwrap when the scheme is the whole condition. Anything else keeps its
        // wrapper, for the reason in the method comment.
        String rest = PREFERS_LIGHT.matcher(PREFERS_DARK.matcher(prelude).replaceAll(""))
                .replaceAll("")
                .replaceAll("(?i)@media|not |and|only|screen|all|[(),\\s]", "");
        return rest.isEmpty() ? 1 : 0;
    }

    private static final Pattern SELECTOR = Pattern.compile("[A-Za-z0-9 \t\r\n.#>+~*:,()\\[\\]='\"_-]{1,400}");
    private static final Pattern SAFE_AT_RULE = Pattern.compile("@(media|supports)[A-Za-z0-9 \t\r\n:()\\-,.&|]*",
            Pattern.CASE_INSENSITIVE);

    private static String rebuildRules(String css, boolean allowRemoteImages, int depth, boolean darkGround) {
        StringBuilder out = new StringBuilder(css.length());
        int i = 0;
        int n = css.length();
        while (i < n) {
            int open = css.indexOf('{', i);
            if (open < 0) break;
            String prelude = css.substring(i, open).trim();
            int close = matchingBrace(css, open);
            if (close < 0) break;
            String block = css.substring(open + 1, close);
            i = close + 1;

            if (prelude.startsWith("@")) {
                // @media and @supports carry real layout intent. @import and @font-face
                // fetch from a third party, which is exactly what we are preventing.
                if (depth > 0 || !SAFE_AT_RULE.matcher(prelude).matches()) continue;
                String inner = rebuildRules(block, allowRemoteImages, depth + 1, darkGround);
                if (inner.isEmpty()) continue;

                // The reader forces a light ground for sender HTML (see wrapDocument),
                // but forcing it does NOT stop the frame answering
                // prefers-color-scheme: dark, because color-scheme sets what the page
                // can render, not what the media query reports. Every current email
                // builder emits a dark-mode block, so on a machine set to dark the
                // sender's own rules were painting white text onto our forced white
                // paper. Measured at a contrast ratio of 1.00: the whole letter was one
                // colour and read as an empty message. That is the reported bug arriving
                // by a second route, and it is why the ground and the media query have to
                // agree rather than only the ground being pinned.
                //
                // So the frame is made to behave as though the operating system were
                // light: a dark-scheme block cannot apply and is dropped, and a
                // light-scheme block always applies and is unwrapped so it fires even
                // when the reader's machine is dark.
                int scheme = colourSchemeIntent(prelude);
                if (scheme < 0) continue;
                if (scheme > 0) { out.append(inner); continue; }

                out.append(prelude).append('{').append(inner).append('}');
                continue;
            }
            if (prelude.isEmpty() || !SELECTOR.matcher(prelude).matches()) continue;
            String decls = sanitizeDeclarations(block, allowRemoteImages, darkGround);
            if (decls.isEmpty()) continue;
            out.append(prelude).append('{').append(decls).append('}');
        }
        return out.toString();
    }

    private static int matchingBrace(String css, int open) {
        int depth = 0;
        for (int i = open; i < css.length(); i++) {
            char c = css.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    /**
     * CSS comments can be spliced into the middle of a keyword to hide it, backslashes
     * hide keywords as CSS escapes, and a literal less-than is the only way out of a
     * style element. All three are removed before anything else looks at the text.
     * Spaces are kept, because font-family values need them, and isPoisonedCss ignores
     * whitespace when it hunts for the dangerous keywords anyway.
     */
    private static String prepareCss(String raw) {
        String css = CSS_COMMENT.matcher(raw).replaceAll("");
        return css.replace("\\", "").replace("<", "").trim();
    }

    /** Whitespace is ignored here, so "java script:" is caught the same as "javascript:". */
    private static boolean isPoisonedCss(String decl) {
        String low = decl.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        for (String bad : CSS_POISON) {
            if (low.contains(bad)) return true;
        }
        return false;
    }

    /**
     * Splits on ";" but not inside url(...) or a quoted string, so a base64 data URI,
     * which is full of semicolons, survives as a single declaration.
     */
    private static List<String> splitDeclarations(String css) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < css.length(); i++) {
            char c = css.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) depth--;
            } else if (c == ';' && depth == 0) {
                parts.add(css.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(css.substring(start));
        return parts;
    }

    /** Rewrites url(...) to "none" unless it is an image we are willing to fetch. */
    private static String filterCssUrls(String css, boolean allowRemoteImages) {
        if (css.toLowerCase(Locale.ROOT).indexOf("url") < 0) return css;
        Matcher m = CSS_URL.matcher(css);
        StringBuilder out = new StringBuilder(css.length());
        while (m.find()) {
            String inner = m.group(1).trim().replaceAll("^['\"]|['\"]$", "");
            String url = cleanUrl(inner);
            boolean keep = url != null && (isInlineData(url)
                    || (allowRemoteImages && (url.regionMatches(true, 0, "http:", 0, 5)
                    || url.regionMatches(true, 0, "https:", 0, 6))));
            String replacement = keep ? "url(\"" + url.replace("\"", "%22") + "\")" : "none";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ------------------------------------------------------------------ document

    /**
     * Wraps a sanitised fragment in a standalone document for the iframe's srcdoc.
     * The CSP here is layer 3: even a defect in the tokeniser above cannot make the
     * frame fetch a tracking pixel while images are off, because default-src is 'none'.
     *
     * <h3>Why sender HTML is always rendered on a light ground</h3>
     *
     * A message written by somebody else carries its own colours, in inline styles,
     * in {@code bgcolor} attributes and in its own stylesheet, and this class
     * deliberately preserves them: stripping a sender's colours mangles every
     * newsletter and every signature. Those colours are chosen against white,
     * because that is what mail clients have always shown. Almost every HTML mail in
     * existence sets something like {@code color:#333} on its body text and sets no
     * background at all.
     *
     * Painting that on a dark ground produces near-black text on near-black, which
     * is the single most common way a dark-themed mail client becomes unusable, and
     * it happened here: the reader showed empty messages that were not empty. There
     * is no safe repair from this side. Overriding the sender's colour breaks the
     * many messages that set a background too, and inverting the document wrecks
     * logos and screenshots.
     *
     * So the ground follows who wrote the body. Sender HTML gets white, the way
     * every serious client renders it. A plain text body is turned into markup by
     * this class, carries no colours of its own, and therefore follows the app's
     * theme and looks native in the dark shell.
     */
    static String wrapDocument(String fragment, boolean allowRemoteImages, String theme) {
        String imgSrc = allowRemoteImages ? "data: https: http:" : "data:";
        boolean dark = "dark".equalsIgnoreCase(theme);
        boolean auto = theme == null || theme.isBlank() || "auto".equalsIgnoreCase(theme);

        String base = dark ? darkVars() : lightVars();
        String autoBlock = auto
                ? "@media (prefers-color-scheme:dark){:root{" + darkVars() + "}}"
                : "";

        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                + "img-src " + imgSrc + "; style-src 'unsafe-inline'; font-src data:; "
                + "script-src '" + HEIGHT_REPORTER_HASH + "'; object-src 'none'; frame-src 'none'; "
                + "form-action 'none'; base-uri 'none'\">"
                + "<meta name=\"referrer\" content=\"no-referrer\">"
                // color-scheme is set explicitly and is not decoration. Without it the
                // user agent paints its own defaults from the operating system setting:
                // scrollbars, text selection, form controls and the autofill wash inside
                // this frame all turn dark on a machine set to dark, on top of a white
                // letter. No amount of colour token work reaches those, because they are
                // not styled by the page at all.
                + "<style>:root{color-scheme:" + (dark ? "dark" : auto ? "light dark" : "light") + ";"
                + base + "}" + autoBlock
                + "html,body{margin:0;padding:0}"
                // Literals, not var(), for the ground and the default text colour.
                // The sender's own stylesheet is emitted inside <body>, after this one,
                // so a ":root{--jcb:...}" declaration in it would win and repaint the
                // surface the whole legibility decision rests on. Everything else may
                // safely go through a custom property; these two may not.
                + "html{background:" + (dark ? "#202020" : "#ffffff") + "}"
                + "body{background:" + (dark ? "#202020" : "#ffffff") + ";"
                + "color:" + (dark ? "#ededed" : "#14212a") + ";"
                + "font:15px/1.65 system-ui,-apple-system,\"Segoe UI\",Roboto,sans-serif;"
                // The letter needs its own margin. This was 2px, so the message text
                // ran into the frame edge while the header directly above it sat 16 to
                // 22px in, and the two never lined up. The frame is a separate document
                // and inherits none of the reader pane's padding, so the inset has to be
                // stated here, and it has to match .rhead in mail.html: 16px on a phone,
                // 22px above it. The media query works because the frame is a full
                // document whose own width is the one being asked about.
                + "padding:10px 16px 36px;word-wrap:break-word;overflow-wrap:break-word}"
                + "@media (min-width:700px){body{padding:12px 22px 40px}}"
                + "a{color:var(--jcl)}"
                + "img{max-width:100%;height:auto}"
                + "table{max-width:100%}"
                // The <font size> attribute is the other half of the small-type problem
                // and it cannot be clamped the way a CSS declaration can, because it is
                // an index from 1 to 7 rather than a length. 1 and 2 are the ones that
                // land under the floor, and they are exactly what an Outlook signature
                // block and a quoted reply chain are written in.
                + "font[size=\"1\"],font[size=\"2\"]{font-size:14px}"
                // Line height only. Nothing here tries to override a size the sender
                // set: an inline style or a class beats a type selector anyway, so such
                // a rule would fire only where no size exists, which is precisely where
                // it is not needed, while risking flattening something deliberate.
                // atLeastReadable does the real work, at the declaration.
                + "p,li,td,th{line-height:1.6}"
                + ".jc-plain{white-space:pre-wrap;font:14px/1.65 ui-monospace,\"Cascadia Mono\",Consolas,monospace;margin:0}"
                + ".jc-empty{color:var(--jcm);font-style:italic}"
                + ".jc-blocked-img{min-width:26px;min-height:26px;border:1px dashed var(--jcd);"
                + "border-radius:4px;background:var(--jcs)}"
                // Last in the body, so it measures a document that has finished being
                // built rather than one still being parsed.
                + "</style></head><body>" + fragment
                + "<script>" + HEIGHT_REPORTER + "</script>"
                + "</body></html>";
    }

    // Colour literals rather than the console's CSS custom properties, because a
    // sandboxed frame is a separate document and inherits nothing from its parent.
    private static String lightVars() {
        return "--jcb:#ffffff;--jct:#14212a;--jcl:#00697f;--jcm:#7b8d99;--jcd:#dde5e8;--jcs:#eef2f3;";
    }

    /**
     * Charcoal, matching {@code --panel} in the reader pane that this frame sits in,
     * so the plain text body and its container are one surface with no visible seam.
     *
     * These were a blue slate ({@code #121c22}, {@code #18242b}, {@code #22323b}) and
     * that is the blue the mailbox kept coming back to after three separate passes
     * declared it removed. It survived because a sandboxed frame is a separate
     * document that inherits nothing, so no amount of work on the parent's tokens
     * reaches these literals. See UI-SPEC section 2: flat charcoal, never navy.
     */
    private static String darkVars() {
        return "--jcb:#202020;--jct:#ededed;--jcl:#7aa8ff;--jcm:#949494;--jcd:#343434;--jcs:#252525;";
    }

    // ------------------------------------------------------------------ escaping

    private static void escapeText(String s, int from, int to, StringBuilder out) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            switch (c) {
                // An "&" that already opens an entity is left as written, otherwise
                // every "&nbsp;" and "R&amp;D" in real mail renders as literal source.
                case '&' -> out.append(startsEntity(s, i, to) ? "&" : "&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
    }

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
            while (j < to && j - ds < 8 && Character.digit(s.charAt(j), radix) >= 0) j++;
            return j > ds && j < to && s.charAt(j) == ';';
        }
        int ns = j;
        while (j < to && j - ns < 32 && Character.isLetterOrDigit(s.charAt(j))) j++;
        return j > ns && j < to && s.charAt(j) == ';';
    }

    /** Every attribute this class emits is double quoted, so these five are enough. */
    private static void escapeAttr(String s, StringBuilder out) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    if (c >= 0x20 || c == '\t') out.append(c);
                }
            }
        }
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int limit = haystack.length() - needle.length();
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private static String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?".indexOf(url.charAt(end - 1)) >= 0) end--;
        return url.substring(0, end);
    }
}
