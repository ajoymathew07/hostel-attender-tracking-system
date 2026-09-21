package in.ac.iitm.hostel.duty.identity.web;

import in.ac.iitm.hostel.duty.identity.application.EmailAlreadyProvisionedException;
import in.ac.iitm.hostel.duty.identity.application.UnknownUserException;
import in.ac.iitm.hostel.duty.identity.application.UserProvisioningService;
import in.ac.iitm.hostel.duty.identity.application.UserSummary;
import in.ac.iitm.hostel.duty.identity.domain.UserRole;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The provisioning screen, {@code /admin/attenders} (section 10.4).
 *
 * <p>This is the whole of account administration: the manager adds a Google address to the
 * allowlist, sets a role, repoints an address at a different account, and deactivates. There is no
 * "delete user" button, because there is no delete — a departed attender's row is what makes their
 * ledger entries and audit rows still name a person (section 3.1, principle 4).
 *
 * <p>The class-level {@code @PreAuthorize} is defence in depth, not the control: the same rule is
 * on {@link UserProvisioningService}'s implementation, which is where section 11.3 requires it.
 * Remove this one and the screen is still manager-only; remove the service one and it is not.
 */
@Controller
@RequestMapping("/admin/attenders")
@PreAuthorize("hasRole('MANAGER')")
class AttenderAdminController {

    private static final String VIEW = "identity/attenders";
    private static final String REDIRECT = "redirect:/admin/attenders";

    private final UserProvisioningService provisioning;

    AttenderAdminController(UserProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("users", provisioning.listUsers());
        model.addAttribute("roles", UserRole.values());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new ProvisionUserForm());
        }
        return VIEW;
    }

    @PostMapping
    String provision(
            @Valid @ModelAttribute("form") ProvisionUserForm form,
            BindingResult errors,
            Model model,
            RedirectAttributes flash) {
        if (errors.hasErrors()) {
            return list(model);
        }
        UserSummary created = provisioning.provision(form.getDisplayName(), form.getEmail(), form.getRole());
        flash.addFlashAttribute("message", created.email() + " added to the allowlist");
        return REDIRECT;
    }

    @PostMapping("/{id}/deactivate")
    String deactivate(@PathVariable long id, RedirectAttributes flash) {
        UserSummary user = provisioning.deactivate(id);
        flash.addFlashAttribute("message", user.email() + " can no longer sign in");
        return REDIRECT;
    }

    @PostMapping("/{id}/reactivate")
    String reactivate(@PathVariable long id, RedirectAttributes flash) {
        UserSummary user = provisioning.reactivate(id);
        flash.addFlashAttribute("message", user.email() + " can sign in again");
        return REDIRECT;
    }

    @PostMapping("/{id}/role")
    String changeRole(@PathVariable long id, @RequestParam UserRole role, RedirectAttributes flash) {
        UserSummary user = provisioning.changeRole(id, role);
        flash.addFlashAttribute("message", user.email() + " is now " + user.role());
        return REDIRECT;
    }

    @PostMapping("/{id}/email")
    String changeEmail(@PathVariable long id, @RequestParam String email, RedirectAttributes flash) {
        UserSummary user = provisioning.changeEmail(id, email);
        flash.addFlashAttribute("message", "allowlist now points at " + user.email());
        return REDIRECT;
    }

    /** A duplicate address is a sentence to the manager, not a stack trace (section 11.6). */
    @ExceptionHandler(EmailAlreadyProvisionedException.class)
    String alreadyProvisioned(EmailAlreadyProvisionedException failure, RedirectAttributes flash) {
        flash.addFlashAttribute("error", failure.getEmail() + " is already on the allowlist");
        return REDIRECT;
    }

    /** A stale screen posting an id that no longer exists. */
    @ExceptionHandler(UnknownUserException.class)
    String unknownUser(RedirectAttributes flash) {
        flash.addFlashAttribute("error", "That user no longer exists — the list has been refreshed");
        return REDIRECT;
    }
}
