package duty.identity.web;

import duty.identity.application.UserProvisioningService.EmailAlreadyProvisionedException;
import duty.identity.application.UserProvisioningService.InvalidEmailException;
import duty.identity.application.UserProvisioningService.LastManagerException;
import duty.identity.application.UserProvisioningService.UnknownUserException;
import duty.identity.application.UserProvisioningService.UserSummary;
import duty.identity.application.UserProvisioningService;
import duty.identity.domain.AppUser.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
 * The provisioning screen, {@code /admin/attenders}.
 *
 * <p>The {@code @PreAuthorize} here is a second layer; the real check is on
 * {@link UserProvisioningService}.
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

    /** A duplicate address becomes a message on the screen. */
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

    /** An address the allowlist will not accept becomes a message on the screen. */
    @ExceptionHandler(InvalidEmailException.class)
    String invalidEmail(InvalidEmailException failure, RedirectAttributes flash) {
        flash.addFlashAttribute(
                "error", "\"" + failure.getEmail() + "\" is not a usable Google address");
        return REDIRECT;
    }

    /** Refuses to remove the last manager. */
    @ExceptionHandler(LastManagerException.class)
    String lastManager(RedirectAttributes flash) {
        flash.addFlashAttribute(
                "error",
                "That would leave nobody able to administer. Add or reactivate another manager first.");
        return REDIRECT;
    }

    /** The "add a user" form. A mutable bean so Spring can bind a failed submission back into it. */
    static class ProvisionUserForm {

        @NotBlank
        @Size(max = 128)
        private String displayName;

        /** The Google account: the allowlist key. */
        @NotBlank
        @Email
        @Size(max = 255)
        private String email;

        @NotNull private UserRole role = UserRole.ATTENDER;

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public UserRole getRole() {
            return role;
        }

        public void setRole(UserRole role) {
            this.role = role;
        }
    }
}
