package com.jarurat.mailer.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Somebody who proved a mailbox password to the mail server, and proved nothing
 * else.
 *
 * The authorities come from Role.MAILBOX and are built here rather than looked up,
 * which is the point: there may well be an app_user row for this same address
 * carrying OWNER, and this principal must not inherit a single permission from it.
 * A mailbox password is checked by Stalwart, not by us, and it is the one secret in
 * the organisation that most people actually know. If it could also open Campaign
 * Studio, the console would be exactly as strong as the weakest mail password.
 *
 * There is no User behind this and there never will be one, so getUser does not
 * exist. LoginAttemptListener tests for AppUserDetails before it saves anything,
 * and that test is what stops a mailbox sign in from writing an app_user row.
 */
public final class MailboxUserDetails implements SignedInUser {

    private static final List<GrantedAuthority> AUTHORITIES =
            Role.MAILBOX.getPermissions().stream()
                    .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p.name()))
                    .toList();

    private final String address;

    public MailboxUserDetails(String address) {
        this.address = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    @Override public Role getRole() { return Role.MAILBOX; }
    @Override public String getFullName() { return address; }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return AUTHORITIES; }

    /**
     * Never the password that was presented. Nothing rechecks it, because Stalwart
     * already did, and a verified secret that has to survive the session lives in
     * MailCredentialStore where it can be dropped on sign out.
     */
    @Override public String getPassword() { return ""; }

    @Override public String getUsername() { return address; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return !address.isEmpty(); }
}
