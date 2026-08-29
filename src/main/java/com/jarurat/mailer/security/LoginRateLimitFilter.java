package com.jarurat.mailer.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Charges every login submission to LoginRateLimiter before it is processed, waits
 * out whatever backoff that buys, and refunds the address when the password turns out
 * to have been right.
 *
 * Placed immediately before UsernamePasswordAuthenticationFilter, which is after the
 * CSRF filter, so a request that never had a token does not get to run up somebody
 * else's counter. It reads the email parameter itself, which is safe on a form post
 * because the container parses and caches the parameter map, so the authentication
 * filter behind it still sees the same values.
 *
 * THE ORDER OF THE THREE STEPS IS THE FIX, and it is the opposite of what this class
 * used to do. It used to read the counter, run the chain, and charge the counter in a
 * finally afterwards, with a bcrypt at strength twelve and a round trip to the mail
 * server in between. Every request that arrived during that gap read a count that had
 * not moved. Measured on the running application under the shipped
 * spring.threads.virtual.enabled: four hundred concurrent guesses at one address, all
 * four hundred reached authentication and none was refused, against a budget of ten.
 * Reserving first closes the gap by construction, because the counter is written
 * before anything slow happens rather than after it. Nothing is charged in a finally
 * any more and nothing needs to be: a request that blows up inside the chain has
 * already paid, which is the behaviour the finally existed to guarantee.
 *
 * The outcome is read back off the SecurityContext rather than from an authentication
 * event, and that is the reason this class owns both halves instead of feeding the
 * limiter from LoginAttemptListener. A form login that fails never reaches the
 * listener at all when the address has no app_user row, which is most of the people
 * this limiter exists for; and the listener has no reliable handle on the request, so
 * it could not produce the client key. Here both are in hand. Spring Security sets
 * the context before it calls the success handler and clears it on the failure path,
 * and neither branch continues the filter chain, so by the time doFilter returns the
 * context holds either the new authentication or nothing at all.
 *
 * A refusal is a 429 and not a redirect: a redirect would look to the caller exactly
 * like a wrong password, which is the one thing this response must not say, and it
 * would let an attacker keep sending requests without ever learning they are being
 * ignored. A refusal can only ever be the per-client cap now. The address budget buys
 * a delay instead, so the correct password is never turned away and there is nobody
 * for an attacker to hold out of their own mail.
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    /**
     * The last-resort body, for a container that hands back no dispatcher for the
     * designed page. Refusing the request matters more than styling the refusal, so
     * this path answers rather than throwing, and it is plain text because whatever
     * has gone wrong by then makes rendering a template a poor bet.
     */
    private static final String FALLBACK_BODY =
            "Too many sign-in attempts. Wait a few minutes and try again.\n";

    private final LoginRateLimiter limiter;
    private final String usernameParameter;
    private final RequestMatcher submission;

    public LoginRateLimitFilter(LoginRateLimiter limiter, String loginPath, String usernameParameter) {
        this.limiter = limiter;
        this.usernameParameter = usernameParameter;
        this.submission = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, loginPath);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!submission.matches(request)) {
            chain.doFilter(request, response);
            return;
        }

        // The raw parameter, not a normalised copy of it. LoginAddress is the only
        // thing allowed to decide what an address counts as, because the limiter
        // keying an address differently from the way it authenticates is exactly the
        // bypass this whole redesign is here to close.
        String address = request.getParameter(usernameParameter);
        String client = LoginRateLimiter.clientKey(request);

        LoginRateLimiter.Decision decision = limiter.reserve(address, client);
        if (decision.refused()) {
            tooManyRequests(request, response, decision.retryAfterSeconds());
            return;
        }
        decision.pause();

        chain.doFilter(request, response);

        // Only the refund is left, because the charge already happened above.
        if (isSignedIn()) limiter.succeeded(address);
    }

    private static boolean isSignedIn() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        return current != null && current.isAuthenticated()
                && !(current instanceof AnonymousAuthenticationToken);
    }

    /**
     * Answers the designed page rather than a paragraph of plain text, which is
     * section 13.6 of the UI specification: a state somebody can actually reach has
     * to look like the rest of the product.
     *
     * Forwarded rather than redirected, so the status stays 429 and the browser is
     * not handed a second URL to sit on and reload. A forward keeps the status and
     * the headers set here, because the container resets the buffer and not the
     * response, and it carries the original POST method, which is why the page
     * accepts every method rather than only GET. The security chain and this filter
     * are both registered for the REQUEST dispatch only, so the forward is not
     * filtered again and cannot loop.
     */
    private static void tooManyRequests(HttpServletRequest request, HttpServletResponse response, long seconds)
            throws ServletException, IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        request.setAttribute(TooManyRequestsPage.RETRY_AFTER_ATTRIBUTE, seconds);
        RequestDispatcher dispatcher = request.getRequestDispatcher(TooManyRequestsPage.PATH);
        if (dispatcher == null) {
            plainText(response);
            return;
        }
        dispatcher.forward(request, response);
    }

    private static void plainText(HttpServletResponse response) throws IOException {
        response.setContentType("text/plain;charset=UTF-8");
        byte[] body = FALLBACK_BODY.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
