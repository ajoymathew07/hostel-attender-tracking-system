package in.ac.iitm.hostel.duty.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.ac.iitm.hostel.duty.audit.application.AuditEntry;
import in.ac.iitm.hostel.duty.audit.application.AuditTrail;
import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.domain.UserRole;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The two invariants the provisioning screen must not be able to break.
 *
 * <p><strong>The audit log may not be made to lie.</strong> {@code audit_event.detail} is a {@code
 * TEXT} column, so a value carrying JSON punctuation produces a row that still inserts and still
 * reads like a record while naming an address that was never stored. The log is the evidence base
 * when an allowlist change or a bonus is disputed (section 11.7), so the address is validated on
 * the way in and escaped on the way out — belt and braces, because only one of the two survives a
 * future caller who skips the other.
 *
 * <p><strong>Somebody must always be able to administer.</strong> ADR-002 answers account recovery
 * with two {@code MANAGER} rows rather than a password; that only works if the last one cannot be
 * switched off from the very screen it is supposed to protect.
 */
@ExtendWith(MockitoExtension.class)
class DefaultUserProvisioningServiceTest {

    private static final String HOSTILE = "a\",\"to\":\"attacker@evil.example.com";

    @Mock private AppUserRepository users;
    @Mock private AuditTrail audit;

    private DefaultUserProvisioningService provisioning() {
        return new DefaultUserProvisioningService(users, audit);
    }

    private static AppUser persisted(UserRole role, String email, boolean active) {
        AppUser user =
                spy(new AppUser("Someone", role, email, Instant.parse("2026-01-01T00:00:00Z"), null));
        // Lenient: the tests that prove an operation is refused never reach the audit row that
        // would read the id, and that absence is the point of those tests.
        lenient().when(user.getId()).thenReturn(7L);
        if (!active) {
            user.deactivate();
        }
        return user;
    }

    private AuditEntry capturedEntry() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }

    // ---- the audit log may not be made to lie ----------------------------------------------

    @Test
    @DisplayName("an address carrying JSON punctuation is refused, so the audit log cannot be forged")
    void refusesAnAddressThatWouldForgeTheAuditDetail() {
        AppUser victim = persisted(UserRole.ATTENDER, "victim@example.com", true);
        when(users.findById(7L)).thenReturn(Optional.of(victim));

        assertThatThrownBy(() -> provisioning().changeEmail(7L, HOSTILE))
                .isInstanceOf(InvalidEmailException.class);

        assertThat(victim.getEmail()).describedAs("the row is untouched").isEqualTo("victim@example.com");
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a quoted local part is a legal address to jakarta @Email, and still refused here")
    void refusesAQuotedLocalPartTheBeanValidatorWouldAccept() {
        assertThatThrownBy(
                        () -> provisioning().provision("Impostor", "\"a\\\"b\"@example.com", UserRole.ATTENDER))
                .isInstanceOf(InvalidEmailException.class);

        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a repoint records JSON whose `to` is exactly the address the row now holds")
    void theAuditDetailAgreesWithTheStoredRow() throws Exception {
        AppUser user = persisted(UserRole.ATTENDER, "old@example.com", true);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.existsByEmail("new@example.com")).thenReturn(false);
        when(users.saveAndFlush(user)).thenReturn(user);

        provisioning().changeEmail(7L, "  NEW@Example.COM  ");

        JsonNode detail = new ObjectMapper().readTree(capturedEntry().detail());
        assertThat(detail.get("from").asText()).isEqualTo("old@example.com");
        assertThat(detail.get("to").asText())
                .describedAs("the log names the address that was actually stored")
                .isEqualTo(user.getEmail())
                .isEqualTo("new@example.com");
    }

    @Test
    @DisplayName("a blank address is refused rather than stored as an unmatchable allowlist key")
    void refusesABlankAddress() {
        assertThatThrownBy(() -> provisioning().provision("Nobody", "   ", UserRole.ATTENDER))
                .isInstanceOf(InvalidEmailException.class);
        assertThatThrownBy(() -> provisioning().provision("Nobody", null, UserRole.ATTENDER))
                .isInstanceOf(InvalidEmailException.class);
    }

    @Test
    @DisplayName("prose that is not an address at all is refused — it would lock the user out silently")
    void refusesSomethingThatIsNotAnAddress() {
        AppUser victim = persisted(UserRole.ATTENDER, "victim@example.com", true);
        when(users.findById(7L)).thenReturn(Optional.of(victim));

        assertThatThrownBy(() -> provisioning().changeEmail(7L, "this is not an email at all"))
                .isInstanceOf(InvalidEmailException.class);

        assertThat(victim.getEmail()).isEqualTo("victim@example.com");
    }

    // ---- somebody must always be able to administer -----------------------------------------

    @Test
    @DisplayName("the last active manager cannot be deactivated — ADR-002's break-glass")
    void refusesToDeactivateTheLastManager() {
        AppUser onlyManager = persisted(UserRole.MANAGER, "manager@example.com", true);
        when(users.findById(7L)).thenReturn(Optional.of(onlyManager));
        when(users.countByRoleAndActiveIsTrue(UserRole.MANAGER)).thenReturn(1L);

        assertThatThrownBy(() -> provisioning().deactivate(7L)).isInstanceOf(LastManagerException.class);

        assertThat(onlyManager.isActive()).describedAs("still able to administer").isTrue();
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("nor demoted to ATTENDER, which removes a manager just as surely")
    void refusesToDemoteTheLastManager() {
        AppUser onlyManager = persisted(UserRole.MANAGER, "manager@example.com", true);
        when(users.findById(7L)).thenReturn(Optional.of(onlyManager));
        when(users.countByRoleAndActiveIsTrue(UserRole.MANAGER)).thenReturn(1L);

        assertThatThrownBy(() -> provisioning().changeRole(7L, UserRole.ATTENDER))
                .isInstanceOf(LastManagerException.class);

        assertThat(onlyManager.getRole()).isEqualTo(UserRole.MANAGER);
    }

    @Test
    @DisplayName("with a second manager still active, deactivating the first is allowed")
    void allowsDeactivatingAManagerWhenAnotherRemains() {
        AppUser manager = persisted(UserRole.MANAGER, "manager@example.com", true);
        when(users.findById(7L)).thenReturn(Optional.of(manager));
        when(users.countByRoleAndActiveIsTrue(UserRole.MANAGER)).thenReturn(2L);
        when(users.saveAndFlush(manager)).thenReturn(manager);

        assertThatCode(() -> provisioning().deactivate(7L)).doesNotThrowAnyException();

        assertThat(manager.isActive()).isFalse();
    }

    @Test
    @DisplayName("the guard is about managers only: an attender is deactivated without consulting it")
    void deactivatingAnAttenderDoesNotConsultTheManagerCount() {
        AppUser attender = persisted(UserRole.ATTENDER, "ravi@example.com", true);
        when(users.findById(7L)).thenReturn(Optional.of(attender));
        when(users.saveAndFlush(attender)).thenReturn(attender);

        provisioning().deactivate(7L);

        assertThat(attender.isActive()).isFalse();
        verify(users, never()).countByRoleAndActiveIsTrue(any());
    }

    @Test
    @DisplayName("promoting somebody to MANAGER never trips the guard")
    void promotingIsAlwaysAllowed() {
        AppUser attender = persisted(UserRole.ATTENDER, "ravi@example.com", true);
        when(users.findById(7L)).thenReturn(Optional.of(attender));
        when(users.saveAndFlush(attender)).thenReturn(attender);

        provisioning().changeRole(7L, UserRole.MANAGER);

        assertThat(attender.getRole()).isEqualTo(UserRole.MANAGER);
        verify(users, never()).countByRoleAndActiveIsTrue(any());
    }

    @Test
    @DisplayName("an already-deactivated manager is not the last one, so demoting them is allowed")
    void anInactiveManagerIsNotTheLastManager() {
        AppUser inactive = persisted(UserRole.MANAGER, "gone@example.com", false);
        when(users.findById(7L)).thenReturn(Optional.of(inactive));
        when(users.saveAndFlush(inactive)).thenReturn(inactive);

        provisioning().changeRole(7L, UserRole.ATTENDER);

        assertThat(inactive.getRole()).isEqualTo(UserRole.ATTENDER);
        verify(users, never()).countByRoleAndActiveIsTrue(any());
    }
}
