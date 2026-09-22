package in.ac.iitm.hostel.duty.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.ac.iitm.hostel.duty.audit.infrastructure.AuditEventRepository;
import in.ac.iitm.hostel.duty.identity.application.EmailAlreadyProvisionedException;
import in.ac.iitm.hostel.duty.identity.application.SignInOutcome;
import in.ac.iitm.hostel.duty.identity.application.SignInRejection;
import in.ac.iitm.hostel.duty.identity.application.SignInService;
import in.ac.iitm.hostel.duty.identity.application.SignedInUser;
import in.ac.iitm.hostel.duty.identity.application.OidcIdentity;
import in.ac.iitm.hostel.duty.identity.application.UnknownUserException;
import in.ac.iitm.hostel.duty.identity.application.UserProvisioningService;
import in.ac.iitm.hostel.duty.identity.application.UserSummary;
import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.domain.UserRole;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Provisioning: the manager's half of the allowlist (sections 11.2, 11.3, item 1.3 of section
 * 13.3).
 *
 * <p>Two things are being proved here at once — that the operations do what the design says
 * (deactivation is a tombstone that blocks the next sign-in, never a delete), and that they are
 * refused to anyone who is not a manager <em>at the service layer</em>, not merely in a controller.
 */
@SpringBootTest
class UserProvisioningIntegrationTest extends AbstractIntegrationTest {

    @Autowired private UserProvisioningService provisioning;
    @Autowired private SignInService signIn;
    @Autowired private AppUserRepository users;
    @Autowired private AuditEventRepository auditEvents;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** A manager principal of the shape the OIDC flow produces, so {@code created_by} is real. */
    private long actAsManager() {
        AppUser manager = users.saveAndFlush(Fixtures.manager());
        SignedInUser principal =
                new SignedInUser(
                        manager.getId(), manager.getEmail(), manager.getDisplayName(), UserRole.MANAGER);
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(principal, null, "ROLE_MANAGER"));
        return manager.getId();
    }

    @Test
    @DisplayName("a manager provisions an attender, and the row records who created it")
    void aManagerProvisionsAnAttender() {
        long managerId = actAsManager();
        String email = Fixtures.unique("new.attender") + "@example.com";

        UserSummary created = provisioning.provision("Selvi", "  " + email.toUpperCase() + " ", UserRole.ATTENDER);

        assertThat(created.email()).describedAs("stored normalised").isEqualTo(email.toLowerCase());
        assertThat(created.role()).isEqualTo(UserRole.ATTENDER);
        assertThat(created.active()).isTrue();
        assertThat(users.findById(created.id()).orElseThrow().getCreatedBy()).isEqualTo(managerId);
        assertThat(provisioning.listUsers()).extracting(UserSummary::id).contains(created.id());
    }

    @Test
    @DisplayName("provisioning is the only way in: a duplicate allowlist email is refused")
    void refusesADuplicateEmail() {
        actAsManager();
        String email = Fixtures.unique("dupe") + "@example.com";
        provisioning.provision("First", email, UserRole.ATTENDER);

        assertThatThrownBy(() -> provisioning.provision("Second", email.toUpperCase(), UserRole.ATTENDER))
                .isInstanceOf(EmailAlreadyProvisionedException.class);
    }

    @Test
    @DisplayName("deactivation blocks the next sign-in immediately, and keeps the row — section 11.2")
    void deactivationBlocksTheNextSignIn() {
        actAsManager();
        String email = Fixtures.unique("lost.phone") + "@example.com";
        UserSummary attender = provisioning.provision("Ravi", email, UserRole.ATTENDER);

        assertThat(signIn.admit(new OidcIdentity(email, true)).admitted())
                .describedAs("signs in while active")
                .isTrue();

        UserSummary deactivated = provisioning.deactivate(attender.id());

        assertThat(deactivated.active()).isFalse();
        SignInOutcome refused = signIn.admit(new OidcIdentity(email, true));
        assertThat(refused.admitted()).isFalse();
        assertThat(refused.rejection()).isEqualTo(SignInRejection.DEACTIVATED);
        assertThat(users.findById(attender.id())).describedAs("tombstoned, never deleted").isPresent();

        assertThat(provisioning.reactivate(attender.id()).active()).isTrue();
        assertThat(signIn.admit(new OidcIdentity(email, true)).admitted()).isTrue();
    }

    @Test
    @DisplayName("a manager sets a user's role, and the role a sign-in returns changes with it")
    void aManagerSetsTheRole() {
        actAsManager();
        String email = Fixtures.unique("promoted") + "@example.com";
        UserSummary user = provisioning.provision("Kumar", email, UserRole.ATTENDER);

        assertThat(provisioning.changeRole(user.id(), UserRole.MANAGER).role()).isEqualTo(UserRole.MANAGER);
        assertThat(signIn.admit(new OidcIdentity(email, true)).user().getRole()).isEqualTo(UserRole.MANAGER);
    }

    @Test
    @DisplayName("a manager repoints the allowlist at a different Google account — the recovery path")
    void aManagerRepointsTheAllowlistEmail() {
        actAsManager();
        UserSummary user = provisioning.provision("Kumar", Fixtures.unique("old") + "@example.com", UserRole.ATTENDER);
        String replacement = Fixtures.unique("recovered") + "@example.com";

        provisioning.changeEmail(user.id(), replacement.toUpperCase());

        assertThat(signIn.admit(new OidcIdentity(replacement, true)).admitted()).isTrue();
    }

    @Test
    @DisplayName("every provisioning action is audited against the user it changed — section 11.7")
    void provisioningIsAudited() {
        long managerId = actAsManager();
        UserSummary user = provisioning.provision("Audited", Fixtures.unique("audited") + "@example.com", UserRole.ATTENDER);
        provisioning.deactivate(user.id());

        assertThat(auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc("app_user", user.id()))
                .extracting(event -> event.getEventType() + "/" + event.getActorId())
                .containsExactly("USER_PROVISIONED/" + managerId, "USER_DEACTIVATED/" + managerId);
    }

    @Test
    @DisplayName("an unknown user id is an error, not a silent no-op")
    void unknownUserIsRejected() {
        actAsManager();

        assertThatThrownBy(() -> provisioning.deactivate(-1L)).isInstanceOf(UnknownUserException.class);
    }

    @Test
    @WithMockUser(roles = "ATTENDER")
    @DisplayName("an attender cannot provision, deactivate or change a role — section 11.3")
    void anAttenderIsRefusedEveryProvisioningOperation() {
        assertThatThrownBy(() -> provisioning.listUsers()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> provisioning.provision("Sneaky", "sneaky@example.com", UserRole.MANAGER))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> provisioning.deactivate(1L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> provisioning.reactivate(1L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> provisioning.changeRole(1L, UserRole.MANAGER)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> provisioning.changeEmail(1L, "x@example.com")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("an anonymous caller cannot reach the service either")
    void anonymousIsRefused() {
        assertThatThrownBy(() -> provisioning.listUsers()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("the manager role alone reaches the service; no assignment is needed for provisioning")
    void aManagerRoleIsEnoughToProvision() {
        assertThat(provisioning.listUsers()).isNotEmpty();
    }
}
