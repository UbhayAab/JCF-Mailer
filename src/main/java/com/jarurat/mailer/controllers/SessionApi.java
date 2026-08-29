package com.jarurat.mailer.controllers;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * How much longer this sign-in has, answered without spending any of it.
 *
 * The session times out after eight hours of inactivity and nothing has ever said
 * so. The first sign is a form post that lands silently on the login page, and on a
 * campaign that somebody has been composing all afternoon that is the whole
 * afternoon. This endpoint is what a countdown on the page reads.
 *
 * <h2>Why the container's own clock cannot be the answer</h2>
 *
 * The obvious implementation is to return {@code HttpSession.getLastAccessedTime()
 * + getMaxInactiveInterval()}. It is wrong here, and wrong in the way that matters:
 * it would make the control lie in the direction of never warning anybody.
 *
 * Tomcat measures idleness from {@code thisAccessedTime}, which
 * {@code StandardSession.access()} sets on every request that carries the session
 * cookie. Two separate things guarantee our own poll is such a request. Spring
 * Security has to load the SecurityContext out of the session to decide whether the
 * caller may have this endpoint at all, and that load calls
 * {@code request.getSession(false)}; and Tomcat's own CoyoteAdapter calls
 * {@code request.getSession(false)} once more at the end of every request, on
 * purpose, because it reads the servlet specification as requiring it. There is no
 * ordering and no annotation that gets underneath either one. A GET that reported
 * the container's clock would therefore reset the clock it was reporting, and a page
 * polling it every minute would hold the session open for as long as the tab stayed
 * open. The countdown would tick down to five minutes, we would raise a dialog, and
 * the session behind it would never actually end. SessionClockTest measures this on a
 * real Tomcat rather than asserting it from the source.
 *
 * <h2>What is answered instead</h2>
 *
 * A session attribute holding the wall-clock time of the last request that was not
 * one of these four endpoints, written by {@link SessionActivityFilter} below. The
 * deadline is that instant plus the container's own {@code maxInactiveInterval}, so
 * the timeout stays configured in exactly one place, {@code
 * server.servlet.session.timeout}, and this class never restates it.
 *
 * That number is always at or behind the container's real deadline, never ahead of
 * it, because every request that moves ours moves the container's too and our own
 * reads move neither. Warning early is the safe direction to be wrong in; warning
 * late is the failure this file exists to remove.
 *
 * <h2>What the GET does write, and why it is not an extension</h2>
 *
 * If the attribute is missing, the GET seeds it from {@code getLastAccessedTime()},
 * which during request N is the access time of request N-1: the page load that
 * brought the script in. That is an instant already in the past, so it moves no
 * deadline; it pins one that was about to drift.
 *
 * Leaving it unseeded was measured and was wrong. Falling back to
 * {@code getLastAccessedTime()} on every read means the answer follows the container's
 * clock, and that clock includes the previous read, so back to back reads reported a
 * deadline creeping forward a few milliseconds at a time - the exact failure the
 * attribute exists to prevent, in miniature and only until the first ordinary
 * request. SessionClockTest measures it at 5ms per read on a real Tomcat with the
 * seed removed, and zero with it in place. It is only ever missing between signing in
 * and the first request that is not one of these four, but a guarantee with a window
 * in it is not a guarantee.
 *
 * The GET does not invalidate the session on expiry. It answers 401 and lets the page
 * offer a route out through {@code /logout}, which is the path that already knows to
 * drop the mailbox password from this process on the way. An expiry that invalidated
 * the session from here would leave that credential resident, and a GET that destroys
 * a session is a surprise sitting inside a request whose whole promise is that it
 * takes nothing away.
 */
@RestController
public class SessionApi {

    /**
     * Wall-clock milliseconds of the last request that counted as the person doing
     * something. Deliberately the activity instant and not the deadline: with the
     * instant stored, a change to server.servlet.session.timeout takes effect on
     * every live session at once instead of only on sessions created afterwards.
     */
    static final String ACTIVE_AT = "jm.session.activeAt";

    /** The contract path. Reachable today by a console session. */
    static final String PATH = "/api/session";

    /**
     * The same two endpoints under the mail prefix.
     *
     * SecurityConfig closes everything outside MAIL_ONLY_PATHS to a session bought
     * with a mailbox password, so a phone signed in to /mail - which the UI
     * specification calls the default on a phone and the product for most people -
     * gets 403 from the contract path above and would have no countdown at all.
     * "/api/mail/**" is on that list and is authenticated-only, so this alias serves
     * both kinds of session today with no change to a file this commit does not own.
     * Delete it once "/api/session/**" is added to MAIL_ONLY_PATHS.
     */
    static final String MAIL_PATH = "/api/mail/session";

    /** The four paths that must not count as activity. See the filter below. */
    static final Set<String> OWN_PATHS =
            Set.of(PATH, PATH + "/extend", MAIL_PATH, MAIL_PATH + "/extend");

    private final long warnSeconds;

    SessionApi(@Value("${app.session.warnSeconds:300}") long warnSeconds) {
        this.warnSeconds = warnSeconds;
    }

    /**
     * @param authenticated   false only ever accompanies a 401, and exists so the page
     *                        can tell an ended session from a network failure
     * @param expired         the session has passed its own deadline, as opposed to
     *                        having been thrown away by the container already
     * @param serverTime      epoch milliseconds on this box, so the browser can
     *                        subtract its own clock skew instead of trusting it. A
     *                        phone whose clock is four minutes fast would otherwise
     *                        show a countdown four minutes short.
     * @param expiresAt       epoch milliseconds, or 0 when the session cannot expire
     * @param timeoutSeconds  the configured inactivity window, so the page can say
     *                        "after 8 hours" without a second copy of the number
     */
    public record SessionState(boolean authenticated,
                               boolean expired,
                               long serverTime,
                               long expiresAt,
                               long remainingSeconds,
                               long timeoutSeconds,
                               long warnSeconds) {
    }

    /**
     * Remaining life, spending none of it.
     *
     * 401 rather than 200-with-a-flag when it is over, so the page has one branch for
     * "this sign-in is finished" whether the deadline passed on our terms or the
     * container threw the session away first. Spring answers the second case with a
     * 401 of its own for anything under /api/**, and a page that had to tell the two
     * apart would get it wrong on the case that happens overnight.
     */
    @GetMapping({PATH, MAIL_PATH})
    public ResponseEntity<SessionState> state(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !signedIn()) return ended();
        SessionState state = read(session);
        return state.expired() ? ended() : ResponseEntity.ok(state);
    }

    /**
     * Buy another full window, because the person said so.
     *
     * This is the only write in the file, and it is a POST behind the CSRF token for
     * that reason. Refused once the deadline has passed: the page has already told
     * that person their session ended, and quietly reviving it would make the
     * sentence they just read false. Signing in again is a few seconds and it is the
     * honest answer.
     */
    @PostMapping({PATH + "/extend", MAIL_PATH + "/extend"})
    public ResponseEntity<SessionState> extend(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !signedIn()) return ended();
        if (read(session).expired()) return ended();
        session.setAttribute(ACTIVE_AT, System.currentTimeMillis());
        return ResponseEntity.ok(read(session));
    }

    /**
     * An authority is not enough on its own: AnonymousAuthenticationToken is
     * "authenticated" and carries ROLE_ANONYMOUS, so a chain change that ever made
     * these paths public would otherwise start reporting a countdown for nobody.
     */
    private static boolean signedIn() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());
    }

    private SessionState read(HttpSession session) {
        long now = System.currentTimeMillis();
        long timeout = session.getMaxInactiveInterval();
        // Zero or negative is the servlet specification's way of saying this session
        // never times out. Reporting a deadline of 0 tells the page to stay quiet
        // rather than invent one.
        if (timeout <= 0) return new SessionState(true, false, now, 0L, 0L, 0L, warnSeconds);

        Object mark = session.getAttribute(ACTIVE_AT);
        long activeAt;
        if (mark instanceof Long value) {
            activeAt = value;
        } else {
            // The access time of the request before this one, which is the instant the
            // container is already counting from. Written down so the next read gets
            // the same answer instead of the container's by then newer one.
            activeAt = session.getLastAccessedTime();
            session.setAttribute(ACTIVE_AT, activeAt);
        }
        long expiresAt = activeAt + timeout * 1000L;
        // Rounded up, so a countdown driven by this never shows 0 while a second of
        // the session is still there to be spent.
        long remaining = Math.max(0L, (expiresAt - now + 999L) / 1000L);
        return new SessionState(true, remaining == 0L, now, expiresAt, remaining, timeout, warnSeconds);
    }

    private ResponseEntity<SessionState> ended() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new SessionState(
                false, true, System.currentTimeMillis(), 0L, 0L, 0L, warnSeconds));
    }

    /**
     * Records that the person did something.
     *
     * Registered after the security chain, so a request that was refused never counts
     * as activity, and mapped at "/*" so it sees static assets and page loads as well
     * as API calls - a page load is the clearest evidence of somebody being there.
     *
     * The four session endpoints are skipped, and that skip is the entire mechanism.
     * A countdown that had to be believed cannot be allowed to feed itself, and a
     * refused extend must not extend anything, which is why the write for a successful
     * extend lives in the controller rather than here.
     *
     * getSession(false) throughout: an unauthenticated GET of the landing page must
     * not be given a session just because this filter walked past it.
     */
    static final class SessionActivityFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            if (!isSessionEndpoint(request)) {
                HttpSession session = request.getSession(false);
                if (session != null) session.setAttribute(ACTIVE_AT, System.currentTimeMillis());
            }
            chain.doFilter(request, response);
        }

        /**
         * Exact paths, not prefixes. "/api/session" is a prefix of
         * "/api/session/extend", and a prefix test would have quietly excused the one
         * request in the set that is supposed to move the deadline. Tomcat normalises
         * "." and ".." out of the URI before any filter sees it, so there is no
         * spelling of these paths that reaches the controller and misses this test.
         */
        private static boolean isSessionEndpoint(HttpServletRequest request) {
            String path = request.getRequestURI();
            String context = request.getContextPath();
            if (context != null && !context.isEmpty() && path.startsWith(context)) {
                path = path.substring(context.length());
            }
            return OWN_PATHS.contains(path);
        }
    }

    /**
     * Order 0 puts this after springSecurityFilterChain, which Spring Boot registers
     * near Integer.MIN_VALUE. That ordering is the difference between counting
     * everything and counting only what was allowed through.
     */
    @Configuration(proxyBeanMethods = false)
    static class Wiring {

        @Bean
        SessionActivityFilter sessionActivityFilter() {
            return new SessionActivityFilter();
        }

        @Bean
        FilterRegistrationBean<SessionActivityFilter> sessionActivityFilterRegistration(
                SessionActivityFilter filter) {
            FilterRegistrationBean<SessionActivityFilter> registration =
                    new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns("/*");
            registration.setOrder(0);
            registration.setName("sessionActivityFilter");
            return registration;
        }
    }
}
