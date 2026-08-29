package com.jarurat.mailer.controllers;

import com.jarurat.mailer.security.LoginLandingHandler;
import com.jarurat.mailer.security.SignedInUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String landing(@AuthenticationPrincipal SignedInUser user, HttpServletRequest request) {
        return user == null ? "landing" : "redirect:" + landingFor(user, request);
    }

    @GetMapping("/login")
    public String login(@AuthenticationPrincipal SignedInUser user, HttpServletRequest request) {
        return user == null ? "login" : "redirect:" + landingFor(user, request);
    }

    /**
     * The console, or a redirect away from it.
     *
     * Two people arrive here who should not stay. A mail-only session has no console
     * permission at all and would otherwise be handed a page whose every panel then
     * 403s, so it is sent to the mailbox instead of being allowed to render one. And a
     * phone lands on the mailbox even when the account is an admin's, because the
     * mailbox is the phone product; "?desktop=1" is how somebody overrides that, and
     * DeviceHints remembers the choice for the session so the redirect this method
     * would otherwise repeat does not undo it on the next link.
     */
    @GetMapping("/app")
    public String console(@AuthenticationPrincipal SignedInUser user, HttpServletRequest request, Model model) {
        // The chain requires authentication here, so null only happens if something
        // ever authenticates with a principal that is not one of ours. A redirect is
        // a better answer to that than a 500 on the console's own front door.
        if (user == null) return "redirect:/login";

        String landing = landingFor(user, request);
        if (!"/app".equals(landing)) return "redirect:" + landing;

        model.addAttribute("userEmail", user.getUsername());
        model.addAttribute("userName", user.getFullName());
        model.addAttribute("userRole", user.getRole().name());
        model.addAttribute("userRoleLabel", user.getRole().getLabel());
        model.addAttribute("permissions", user.getRole().getPermissions().stream().map(Enum::name).toList());
        return "console";
    }

    /** The same rule the login success handler applies, asked again on a later GET. */
    private static String landingFor(SignedInUser user, HttpServletRequest request) {
        return LoginLandingHandler.landingFor(user.getAuthorities(), request);
    }
}
