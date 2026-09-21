package in.ac.iitm.hostel.duty.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.domain.UserRole;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@code app_user} — the Google allowlist (data model section 3.3).
 *
 * <p>The table stores no credential at all, so there is nothing here about passwords. What it does
 * store is the decision of <em>whether</em> a verified Google identity may in, which is what these
 * tests are about.
 */
@SpringBootTest
class AppUserIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AppUserRepository users;

    @Test
    @DisplayName("uq_user_email: the allowlist key is unique")
    void emailIsUnique() {
        AppUser first = users.saveAndFlush(Fixtures.attender());

        assertThatThrownBy(
                        () ->
                                users.saveAndFlush(
                                        new AppUser(
                                                "Impostor",
                                                UserRole.ATTENDER,
                                                first.getEmail(),
                                                Instant.parse("2026-01-02T00:00:00Z"),
                                                null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a case- or space-varying address matches the same person — E20c")
    void emailMatchingIsNormalised() {
        String address = Fixtures.unique("Attender") + "@Example.COM";
        AppUser stored =
                users.saveAndFlush(
                        new AppUser(
                                "Ravi",
                                UserRole.ATTENDER,
                                "  " + address + " ",
                                Instant.parse("2026-01-01T00:00:00Z"),
                                null));

        assertThat(stored.getEmail()).isEqualTo(address.toLowerCase(java.util.Locale.ROOT));
        assertThat(users.findByEmail(AppUser.normalizeEmail("   " + address.toUpperCase(java.util.Locale.ROOT) + "  ")))
                .isPresent();
        assertThat(users.existsByEmail(stored.getEmail())).isTrue();
    }

    @Test
    @DisplayName("deactivating blocks the next sign-in; the row survives so the audit trail stays intact — E20b")
    void deactivationIsATombstoneNotADelete() {
        AppUser user = users.saveAndFlush(Fixtures.attender());
        String email = user.getEmail();

        user.deactivate();
        user = users.saveAndFlush(user);

        assertThat(users.findByEmailAndActiveIsTrue(email))
                .describedAs("an allowlisted but inactive user is rejected")
                .isEmpty();
        assertThat(users.findByEmail(email)).describedAs("but the row is still there").isPresent();

        user.reactivate();
        users.saveAndFlush(user);
        assertThat(users.findByEmailAndActiveIsTrue(email)).isPresent();
    }

    @Test
    @DisplayName("a manager can repoint a user at a different Google account — the recovery path")
    void emailCanBeChanged() {
        AppUser user = users.saveAndFlush(Fixtures.attender());
        String replacement = Fixtures.unique("recovered") + "@example.com";

        user.changeEmail("  " + replacement.toUpperCase(java.util.Locale.ROOT) + "  ");
        users.saveAndFlush(user);

        assertThat(users.findByEmail(replacement)).isPresent();
    }

    @Test
    @DisplayName("every column round-trips through PostgreSQL as written")
    void allColumnsRoundTrip() {
        AppUser manager = users.saveAndFlush(Fixtures.manager());
        AppUser provisioned =
                users.saveAndFlush(
                        new AppUser(
                                "Selvi",
                                UserRole.ATTENDER,
                                Fixtures.unique("selvi") + "@example.com",
                                Instant.parse("2026-01-05T09:30:00Z"),
                                manager.getId()));
        provisioned.recordLogin(Instant.parse("2026-02-01T04:15:00Z"));
        users.saveAndFlush(provisioned);

        AppUser reloaded = users.findById(provisioned.getId()).orElseThrow();
        assertThat(reloaded.getDisplayName()).isEqualTo("Selvi");
        assertThat(reloaded.getRole()).isEqualTo(UserRole.ATTENDER);
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getCreatedAt()).isEqualTo(Instant.parse("2026-01-05T09:30:00Z"));
        assertThat(reloaded.getCreatedBy()).isEqualTo(manager.getId());
        assertThat(reloaded.getLastLoginAt()).isEqualTo(Instant.parse("2026-02-01T04:15:00Z"));
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("ck_user_role admits only MANAGER and ATTENDER, and managers are listed for the break-glass path")
    void rolesAreConstrained() {
        AppUser manager = users.saveAndFlush(Fixtures.manager());

        assertThat(users.findByRoleAndActiveIsTrueOrderByDisplayNameAsc(UserRole.MANAGER))
                .extracting(AppUser::getId)
                .contains(manager.getId());
        assertThat(UserRole.values()).containsExactly(UserRole.MANAGER, UserRole.ATTENDER);
    }
}
