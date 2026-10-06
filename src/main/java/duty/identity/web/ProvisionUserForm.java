package duty.identity.web;

import duty.identity.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The "add a user" form. A mutable bean so Spring can bind a failed submission back into it. */
class ProvisionUserForm {

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
