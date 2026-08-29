package com.jarurat.mailer.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;

/**
 * The page behind a 429 on the login form.
 *
 * It exists because the refusal used to be a paragraph of text/plain, which is the
 * one state on the whole sign-in path that had never been designed. Section 13.6 of
 * the UI specification is explicit that errors are designed states rather than
 * whatever the browser does with a bare body, and this one is not hypothetical: it is
 * what somebody in an office behind the same nginx as an attacker sees.
 *
 * Mapped for every method rather than for GET, and that is not tidiness. The refusal
 * is a forward from LoginRateLimitFilter, a forward keeps the method of the request
 * it came from, and the request that gets refused is the login POST. A @GetMapping
 * here would answer that forward with 405 and the person would get the container's
 * own error page, which is the exact outcome this class exists to avoid.
 */
@Controller
public class TooManyRequestsPage {

    /** The path the filter forwards to. SecurityConfig also lists it as public. */
    public static final String PATH = "/too-many-requests";

    /** Where the filter leaves the wait it just told the caller about. */
    public static final String RETRY_AFTER_ATTRIBUTE = "jarurat.login.retryAfterSeconds";

    @RequestMapping(PATH)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ModelAndView page(
            @RequestAttribute(name = RETRY_AFTER_ATTRIBUTE, required = false) Long retryAfterSeconds) {
        ModelAndView view = new ModelAndView("too-many-requests");
        view.addObject("waitMinutes", minutes(retryAfterSeconds));
        return view;
    }

    /**
     * Rounded up and never zero, because "try again in 0 minutes" reads as a bug, and
     * clamped to the window so a wait can never be reported as longer than the
     * counter can possibly hold. A missing attribute means somebody reached this page
     * without being refused, and the whole window is the honest thing to say then.
     */
    private static int minutes(Long seconds) {
        long value = seconds == null ? LoginRateLimiter.WINDOW_MINUTES * 60L : seconds;
        long rounded = (value + 59) / 60;
        return (int) Math.min(Math.max(rounded, 1), LoginRateLimiter.WINDOW_MINUTES);
    }
}
