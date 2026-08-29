package com.jarurat.mailer.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A mailbox password is the one secret most of the organisation actually knows, and
 * a session bought with it must be worth a mailbox and nothing else. These tests are
 * the guard on that: they pin the exact permission set rather than a sample of it, so
 * a permission added to Role.MAILBOX by accident fails here instead of quietly
 * widening what every mail password in the building is worth.
 */
class MailboxPrincipalTest {

    @Test
    @DisplayName("the MAILBOX role is exactly MAIL_READ and MAIL_SEND")
    void roleHoldsOnlyMailPermissions() {
        assertThat(Role.MAILBOX.getPermissions())
                .isEqualTo(EnumSet.of(Permission.MAIL_READ, Permission.MAIL_SEND));
        assertThat(Role.MAILBOX.getLabel()).isEqualTo("Mailbox");
        assertThat(Role.MAILBOX.getDescription()).contains("Mail only");
    }

    @Test
    @DisplayName("the MAILBOX role cannot send a campaign or touch the subscriber base")
    void roleCannotReachTheConsole() {
        assertThat(Role.MAILBOX.can(Permission.CAMPAIGNS_SEND)).isFalse();
        assertThat(Role.MAILBOX.can(Permission.CAMPAIGNS_READ)).isFalse();
        assertThat(Role.MAILBOX.can(Permission.SUBSCRIBERS_READ)).isFalse();
        assertThat(Role.MAILBOX.can(Permission.TRANSACTIONAL_SEND)).isFalse();
        assertThat(Role.MAILBOX.can(Permission.MAILBOX_MANAGE)).isFalse();
        assertThat(Role.MAILBOX.can(Permission.TEAM_WRITE)).isFalse();
        assertThat(Role.MAILBOX.can(Permission.SETTINGS_WRITE)).isFalse();
    }

    @Test
    @DisplayName("the principal grants those two authorities and no others")
    void principalGrantsExactlyTwoAuthorities() {
        MailboxUserDetails principal = new MailboxUserDetails("Priya@Jarurat.Care");

        assertThat(principal.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .containsExactlyInAnyOrder("MAIL_READ", "MAIL_SEND");
        assertThat(principal.getRole()).isEqualTo(Role.MAILBOX);
        assertThat(principal.getUsername()).isEqualTo("priya@jarurat.care");
        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.isAccountNonLocked()).isTrue();
    }

    @Test
    @DisplayName("the presented password is not kept on the principal")
    void principalKeepsNoPassword() {
        assertThat(new MailboxUserDetails("priya@jarurat.care").getPassword()).isEmpty();
    }

    @Test
    @DisplayName("a mailbox principal is not an AppUserDetails, so nothing writes app_user for it")
    void principalIsNotAConsoleUser() {
        // LoginAttemptListener.onSuccess saves details.getUser(). There is no User
        // behind a mailbox login, so this type test is what stops a sign in from
        // inserting a row for an address that deliberately has no console account.
        assertThat((Object) new MailboxUserDetails("priya@jarurat.care"))
                .isNotInstanceOf(AppUserDetails.class);
    }

    @Test
    @DisplayName("a mail-only authority set is recognised, a console one is not")
    void mailOnlyIsRecognised() {
        assertThat(LoginLandingHandler.isMailOnly(authorities("MAIL_READ", "MAIL_SEND"))).isTrue();
        assertThat(LoginLandingHandler.isMailOnly(authorities("MAIL_READ"))).isTrue();
        assertThat(LoginLandingHandler.isMailOnly(authorities("MAIL_READ", "CAMPAIGNS_SEND"))).isFalse();
        assertThat(LoginLandingHandler.isMailOnly(Set.of())).isFalse();

        // Every shipped console role has at least one permission outside the mail
        // pair, or it would be routed to the mailbox and never see the console.
        for (Role role : Role.values()) {
            if (role == Role.MAILBOX) continue;
            assertThat(LoginLandingHandler.isMailOnly(
                    role.getPermissions().stream()
                            .map(p -> (GrantedAuthority) p::name).toList()))
                    .as("role %s", role)
                    .isFalse();
        }
    }

    private static List<GrantedAuthority> authorities(String... names) {
        return java.util.Arrays.stream(names).map(n -> (GrantedAuthority) () -> n).toList();
    }
}
