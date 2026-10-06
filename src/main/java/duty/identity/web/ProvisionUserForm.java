package duty.identity.web;

import duty.identity.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The "add a user" form on the provisioning screen.
 *
 * <p>A mutable bean rather than a record because Spring MVC binds an invalid submission back into
 * it field by field, so the manager's half-filled form survives a validation failure.
 */
class ProvisionUserForm {

    @NotBlank
    @Size(max = 128)
    private String displayName;

    /** The Google account. This is the allowlist key, so it is the only thing that lets them in. */
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
