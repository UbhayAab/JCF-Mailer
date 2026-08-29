package com.jarurat.mailer.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refusal over real HTTP, through a real container, because the thing being
 * claimed cannot be proved anywhere else.
 *
 * The 429 is a forward to TooManyRequestsPage, and MockMvc records a forward rather
 * than performing one: LoginChainTest can see that the filter asked for the right
 * path and not that anything was rendered at the end of it. Everything that could
 * still be wrong lives in that gap. A forward resets the buffer and not the response,
 * so the status and the Retry-After header have to survive it; the forward carries the
 * POST method of the login submission, so a page mapped only for GET would answer 405;
 * and the security chain is registered for the request dispatch, so a forward that was
 * filtered again would either loop or bounce to the login page.
 *
 * The budget is spent through the limiter bean rather than by sending thirty real
 * logins, because each of those is a bcrypt comparison at strength twelve and the
 * counting is LoginRateLimiterTest's business. What has to happen over the wire is the
 * one request that gets refused.
 *
 * The client is the one in the JDK rather than a test helper, so that this file adds
 * no dependency to a pom it does not own, and it is told not to follow redirects: a
 * refusal that had quietly become a redirect to the login page has to fail here rather
 * than be followed and pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "jarurat.mail.jmap-url=http://127.0.0.1:1/jmap/")
class TooManyRequestsPageHttpTest {

    @LocalServerPort
    private int port;

    @Autowired
    private LoginRateLimiter limiter;

    @Test
    @DisplayName("a refused login is answered with the designed page, rendered, at 429")
    void theRefusalIsTheDesignedPageOverRealHttp() throws Exception {
        limiter.reset();
        for (int i = 0; i < 200; i++) {
            limiter.reserve("person" + i + "@jarurat.care", "203.0.113.9");
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // The login page issues the token eagerly, and the plain request handler wants
        // the raw cookie value back, which is what a browser sends.
        HttpResponse<String> page = client.send(
                HttpRequest.newBuilder(uri("/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String cookie = tokenCookie(page);
        assertThat(cookie).as("the login page did not set XSRF-TOKEN").isNotNull();
        String token = cookie.substring(cookie.indexOf('=') + 1);

        String form = field("email", "someone@jarurat.care")
                + "&" + field("password", "guess")
                + "&" + field("_csrf", token);

        HttpResponse<String> refused = client.send(
                HttpRequest.newBuilder(uri("/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Cookie", cookie)
                        // Exactly the shape nginx sends: the caller's claim, then the
                        // peer it saw. Only the last element is the real one.
                        .header("X-Forwarded-For", "198.51.100.7, 203.0.113.9")
                        .header(ClientIp.TRUSTED_HEADER, "203.0.113.9")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.headers().firstValue("Retry-After")).isPresent();
        assertThat(refused.headers().firstValue("Location")).isEmpty();
        assertThat(refused.headers().firstValue("Content-Type").orElse("")).contains("text/html");

        String body = refused.body();
        assertThat(body).contains("Too many attempts");
        assertThat(body).contains("/css/style.css");
        assertThat(body).contains("#i-shield");
        // The wait is rendered rather than left as a Thymeleaf expression on the page.
        assertThat(body).doesNotContain("waitMinutes");
        // The submitted password must not come back on the page that says no.
        assertThat(body).doesNotContain("guess");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String field(String name, String value) {
        return name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String tokenCookie(HttpResponse<String> response) {
        List<String> cookies = response.headers().allValues("Set-Cookie");
        for (String cookie : cookies) {
            if (cookie.startsWith("XSRF-TOKEN=")) {
                int end = cookie.indexOf(';');
                return end < 0 ? cookie : cookie.substring(0, end);
            }
        }
        return null;
    }
}
