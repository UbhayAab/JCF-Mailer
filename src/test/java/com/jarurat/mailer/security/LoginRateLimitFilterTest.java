package com.jarurat.mailer.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of the limiter that decides what happens to a submission before it is
 * processed, and the half that reads the outcome back off the SecurityContext.
 *
 * The outcome reading is the fragile part and half the reason this test exists. It
 * rests on Spring Security setting the context before the success handler runs and
 * clearing it on the failure path, neither of which is visible from this file, and
 * getting it backwards would either stop counting failures entirely or slow people
 * down after ten good logins.
 *
 * The other half is the order of the three steps. Charging the counter has to happen
 * before the chain runs rather than in a finally after it, and the delay has to be
 * taken before the attempt is processed rather than after the answer is known,
 * because a delay on the way out slows the answer down without slowing the guessing
 * down at all.
 */
class LoginRateLimitFilterTest {

    private static final String ADDRESS = "priya@jarurat.care";

    private final LoginRateLimiter limiter = new LoginRateLimiter();
    private final LoginRateLimitFilter filter =
            new LoginRateLimitFilter(limiter, "/login", "email");

    @BeforeEach
    void freshProcess() {
        limiter.reset();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("past its budget an address is slowed down and still allowed through")
    void theBudgetBuysADelayAndTheAttemptStillHappens() throws Exception {
        for (int i = 0; i < LoginRateLimiter.PER_ADDRESS; i++) {
            MockHttpServletResponse response = submit(ADDRESS, false);
            assertThat(response.getStatus()).as("attempt %d", i + 1).isEqualTo(200);
        }

        MockHttpServletRequest request = post(ADDRESS);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        long startedAt = System.nanoTime();
        filter.doFilter(request, response, chain);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        // Twenty five milliseconds of slack for a coarse clock, which is nowhere near
        // enough to let an undelayed request through: without the pause this returns
        // in under a millisecond.
        assertThat(millis).isGreaterThanOrEqualTo(LoginRateLimiter.FIRST_DELAY_MILLIS - 25);
        // Delayed, not refused. The correct password submitted here would still work,
        // which is the entire point of the redesign.
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("a client over its cap gets the designed 429 and never reaches authentication")
    void theClientCapIsRefusedWithTheDesignedPage() throws Exception {
        // Distinct addresses, because this is the sweep the client cap exists for and
        // because no address is then anywhere near its own budget.
        for (int i = 0; i < LoginRateLimiter.PER_CLIENT; i++) {
            assertThat(submit("person" + i + "@jarurat.care", false).getStatus())
                    .as("attempt %d", i + 1).isEqualTo(200);
        }

        MockHttpServletRequest request = post("fresh@jarurat.care");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(response.getHeader("Retry-After"))).isPositive();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        // The refusal is the designed page rather than a paragraph of plain text,
        // which is section 13.6 of the UI specification.
        assertThat(response.getForwardedUrl()).isEqualTo(TooManyRequestsPage.PATH);
        assertThat(request.getAttribute(TooManyRequestsPage.RETRY_AFTER_ATTRIBUTE)).isNotNull();
        // The request must not have reached the authentication filter behind it.
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("a successful sign-in clears the address, so a run of typos costs nothing later")
    void successClearsTheAddress() throws Exception {
        for (int i = 0; i < LoginRateLimiter.PER_ADDRESS - 1; i++) submit(ADDRESS, false);

        assertThat(submit(ADDRESS, true).getStatus()).isEqualTo(200);

        long startedAt = System.nanoTime();
        for (int i = 0; i < LoginRateLimiter.PER_ADDRESS; i++) {
            assertThat(submit(ADDRESS, false).getStatus())
                    .as("attempt %d after the successful one", i + 1).isEqualTo(200);
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        // The whole budget again, and none of it delayed. Without the refund the
        // counter would still stand at nine and the second of these would sleep, so
        // the elapsed time is what actually proves the address was cleared.
        assertThat(millis).isLessThan(LoginRateLimiter.FIRST_DELAY_MILLIS);
    }

    @Test
    @DisplayName("the limiter only looks at the login post, not at fetching the page")
    void onlyTheSubmissionIsLimited() throws Exception {
        for (int i = 0; i < LoginRateLimiter.PER_ADDRESS * 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
            request.setParameter("email", ADDRESS);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }

        assertThat(limiter.trackedAddresses()).isZero();
    }

    /**
     * Runs one POST through the filter. When signedIn is true the chain does what the
     * authentication filter does on success: it puts an authenticated token in the
     * context and does not continue the chain.
     */
    private MockHttpServletResponse submit(String address, boolean signedIn) throws Exception {
        MockHttpServletRequest request = post(address);
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();

        FilterChain chain = (req, res) -> {
            if (signedIn) {
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                ADDRESS, null, List.of(new SimpleGrantedAuthority("MAIL_READ"))));
            }
        };
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest post(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setParameter("email", address);
        request.setParameter("password", "whatever");
        return request;
    }
}
