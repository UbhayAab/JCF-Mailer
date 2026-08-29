package com.jarurat.mailer.security;

import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.webmail.MailboxAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The moment after the password is accepted. Two things have to hold here or the
 * change is not worth making: the mailbox opens on the console password when the two
 * match, and nothing about that attempt can turn a good login into a failed one.
 */
class LoginLandingHandlerTest {

    private static final String ADDRESS = "priya@jarurat.care";
    private static final String PASSWORD = "same-password-for-both";
    private static final String IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    private static final String WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";

    private final MailboxAccess mailboxes = mock(MailboxAccess.class);
    private final LoginLandingHandler handler = new LoginLandingHandler(mailboxes);

    @Test
    @DisplayName("a mail-only session lands on the mailbox with the mailbox already open")
    void mailOnlyOpensBeforeTheRedirect() throws Exception {
        MockHttpServletRequest request = loginPost(WINDOWS);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, mailboxSession());

        // Synchronous on purpose: this person has no console to fall back to, and a
        // background attempt would race the redirect and prompt for a password they
        // have just proved.
        verify(mailboxes).openIfUnset(any(), eq(ADDRESS), eq(PASSWORD));
        assertThat(response.getRedirectedUrl()).isEqualTo("/mail");
    }

    @Test
    @DisplayName("a console session on a laptop lands on the console and still offers the password")
    void consoleSessionOffersInTheBackground() throws Exception {
        MockHttpServletRequest request = loginPost(WINDOWS);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, consoleSession());

        assertThat(response.getRedirectedUrl()).isEqualTo("/app");
        verify(mailboxes, timeout(2000)).openIfUnset(any(), eq(ADDRESS), eq(PASSWORD));
    }

    @Test
    @DisplayName("a console session lands on the console, on a phone as much as a laptop")
    void consoleSessionLandsOnTheConsoleEvenOnAPhone() throws Exception {
        // The account decides this, not the device. Signing in on a phone with the
        // account that runs Campaign Studio used to land on the inbox, with the console
        // reachable only by knowing to add a query parameter, which reads as the sign in
        // having gone somewhere unintended. Somebody who only has a mailbox is unaffected:
        // their account holds nothing else and they are answered before this rule.
        MockHttpServletRequest request = loginPost(IPHONE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, consoleSession());

        assertThat(response.getRedirectedUrl()).isEqualTo("/app");
    }

    @Test
    @DisplayName("a mail server that rejects the password, or is down, never fails the login")
    void mailFailureIsSwallowed() {
        doThrow(new MailException(MailException.Kind.TRANSPORT, "Could not reach the mail server."))
                .when(mailboxes).openIfUnset(any(), any(), any());

        MockHttpServletRequest request = loginPost(WINDOWS);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatCode(() -> handler.onAuthenticationSuccess(request, response, mailboxSession()))
                .doesNotThrowAnyException();
        assertThat(response.getRedirectedUrl()).isEqualTo("/mail");
    }

    @Test
    @DisplayName("no password on the request means nothing is offered")
    void noPasswordMeansNoOffer() throws Exception {
        MockHttpServletRequest request = loginPost(WINDOWS);
        request.removeParameter(LoginLandingHandler.PASSWORD_PARAMETER);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, consoleSession());

        verify(mailboxes, never()).openIfUnset(any(), any(), any());
        assertThat(response.getRedirectedUrl()).isEqualTo("/app");
    }

    @Test
    @DisplayName("a phone that chose Campaign Studio on the landing page is sent to Campaign Studio")
    void theDesktopChoiceSurvivesTheLogin() {
        // The landing page links to /login?desktop=1, and the flag used to die twice
        // over: the form did not carry it into the POST, and session fixation issues a
        // new session on success that copies only SPRING_SECURITY_ attributes, so
        // anything the GET had stored was gone by the time this handler ran. The
        // hidden field in login.html carries it on the POST instead, which is read
        // here, after the new session exists.
        MockHttpServletRequest request = loginPost(IPHONE);
        request.setParameter(DeviceHints.PARAMETER, "1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatCode(() -> handler.onAuthenticationSuccess(request, response, consoleSession()))
                .doesNotThrowAnyException();

        assertThat(response.getRedirectedUrl()).isEqualTo("/app");
        // And remembered, so PageController gives the same answer on every later GET
        // of "/" and "/app" instead of bouncing the phone back to the mailbox.
        assertThat(request.getSession(false).getAttribute(DeviceHints.SESSION_DESKTOP))
                .isEqualTo(Boolean.TRUE);
    }

    private static MockHttpServletRequest loginPost(String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.addHeader("User-Agent", userAgent);
        request.setParameter("email", ADDRESS);
        request.setParameter(LoginLandingHandler.PASSWORD_PARAMETER, PASSWORD);
        request.setSession(new MockHttpSession());
        return request;
    }

    private static Authentication mailboxSession() {
        MailboxUserDetails principal = new MailboxUserDetails(ADDRESS);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
    }

    private static Authentication consoleSession() {
        List<GrantedAuthority> admin = List.of(
                new SimpleGrantedAuthority("MAIL_READ"),
                new SimpleGrantedAuthority("MAIL_SEND"),
                new SimpleGrantedAuthority("CAMPAIGNS_SEND"));
        return UsernamePasswordAuthenticationToken.authenticated(ADDRESS, null, admin);
    }
}
