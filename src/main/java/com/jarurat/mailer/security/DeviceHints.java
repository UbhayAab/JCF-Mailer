package com.jarurat.mailer.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Is this a phone, and does the person want the console anyway.
 *
 * One place, because the answer is used twice on two different sides of the login
 * and the two must not be allowed to drift: the success handler picks the landing
 * page from it, and PageController repeats the decision on every later GET of "/"
 * and "/app". If those ever disagreed, a phone would land on /mail and be bounced
 * back to /app by its own bookmark.
 */
public final class DeviceHints {

    /**
     * Survives for the session so the choice is not lost on the next link. Named
     * for what it is rather than for the query parameter, because the parameter is
     * only how it gets set.
     */
    static final String SESSION_DESKTOP = "jarurat.ui.desktop";

    static final String PARAMETER = "desktop";

    /**
     * Deliberately narrow. Everything not listed is treated as a laptop, including
     * every tablet: an iPad has reported a desktop Safari user agent since iPadOS
     * 13 and its screen is console sized anyway, so guessing at tablets only
     * produces wrong answers in both directions. "android" alone is not enough
     * either, since Android televisions and Android tablets both carry it and only
     * a phone build adds "mobile".
     */
    private static final Pattern MOBILE_UA = Pattern.compile(
            "iphone|ipod|windows phone|iemobile|blackberry|bb10|opera mini"
                    + "|android.*mobile|mobile.*firefox|silk/.*mobile");

    private DeviceHints() {}

    /**
     * The client hint is preferred over the user agent because it is the browser's
     * own answer rather than our guess at one. Sec-CH-UA-Mobile is a structured
     * boolean the browser derives from the form factor it is actually running on,
     * it needs no pattern to read, and it stays correct when a vendor changes its
     * user agent string. The user agent is the fallback precisely because it is
     * being frozen and reduced: Chrome already ships a shortened one, Safari has
     * pinned its version for years, and every pattern written against it is a
     * standing bet that no vendor changes the wording. Only "?1" and "?0" are
     * accepted; anything else means a proxy rewrote the header and we should not
     * trust it over the user agent.
     */
    public static boolean isPhone(HttpServletRequest request) {
        if (request == null) return false;

        String hint = request.getHeader("Sec-CH-UA-Mobile");
        if (hint != null) {
            String value = hint.trim();
            if ("?1".equals(value)) return true;
            if ("?0".equals(value)) return false;
        }

        String agent = request.getHeader("User-Agent");
        return agent != null && MOBILE_UA.matcher(agent.toLowerCase(Locale.ROOT)).find();
    }

    /**
     * The escape hatch. "?desktop=1" says show me the console on this phone anyway,
     * and it is remembered for the session so the choice survives the redirect it
     * causes and every link followed after it. "?desktop=0" gives the phone default
     * back, because a switch that only goes one way strands whoever pressed it.
     *
     * A session is created only when the parameter is actually present. Reading the
     * flag must never mint a session for an anonymous visitor to the landing page.
     */
    public static boolean prefersDesktop(HttpServletRequest request) {
        if (request == null) return false;

        String choice = request.getParameter(PARAMETER);
        if (choice != null) {
            boolean wanted = "1".equals(choice) || "true".equalsIgnoreCase(choice)
                    || "yes".equalsIgnoreCase(choice);
            HttpSession session = request.getSession(wanted);
            if (session != null) {
                if (wanted) session.setAttribute(SESSION_DESKTOP, Boolean.TRUE);
                else session.removeAttribute(SESSION_DESKTOP);
            }
            return wanted;
        }

        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_DESKTOP));
    }

    /** A phone that has not asked for the console. */
    public static boolean wantsMailbox(HttpServletRequest request) {
        return isPhone(request) && !prefersDesktop(request);
    }
}
