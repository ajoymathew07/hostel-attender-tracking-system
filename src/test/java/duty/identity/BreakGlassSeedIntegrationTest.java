package duty.identity;

import static org.assertj.core.api.Assertions.assertThat;

import duty.identity.application.OidcIdentity;
import duty.identity.application.SignInService;
import duty.identity.domain.AppUser;
import duty.identity.domain.UserRole;
import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The two seeded {@code MANAGER} rows — "break-glass is a row, not a mechanism" (ADR-002, item 1.3
 * of section 13.3).
 *
 * <p>The second row answers "what if the manager loses their Google account". With no password in
 * the schema there is nothing to reset, so recovery is the <em>other</em> manager repointing the
 * allowlist — which requires that other manager to exist from the first deployment onwards.
 */
@SpringBootTest
class BreakGlassSeedIntegrationTest extends AbstractIntegrationTest {

    private static final String MANAGER_PLACEHOLDER = "manager.placeholder@replace-me.invalid";
    private static final String BREAK_GLASS_PLACEHOLDER = "breakglass.placeholder@replace-me.invalid";

    @Autowired private AppUserRepository users;
    @Autowired private SignInService signIn;

    @Test
    @DisplayName("two active MANAGER rows exist from the migration, so neither can be locked out alone")
    void twoManagersAreSeeded() {
        List<AppUser> seeded =
                List.of(MANAGER_PLACEHOLDER, BREAK_GLASS_PLACEHOLDER).stream()
                        .map(email -> users.findByEmail(email).orElseThrow())
                        .toList();

        assertThat(seeded)
                .hasSize(2)
                .allSatisfy(
                        user -> {
                            assertThat(user.getRole()).isEqualTo(UserRole.MANAGER);
                            assertThat(user.isActive()).isTrue();
                            assertThat(user.getCreatedBy())
                                    .describedAs("seeded, not provisioned by anyone")
                                    .isNull();
                        });
    }

    @Test
    @DisplayName("both seeded addresses are obvious placeholders in the reserved .invalid domain")
    void theSeededAddressesAreObviousPlaceholders() {
        assertThat(List.of(MANAGER_PLACEHOLDER, BREAK_GLASS_PLACEHOLDER))
                .allSatisfy(
                        email -> {
                            assertThat(email)
                                    .describedAs("RFC 2606 reserves .invalid, so no Google account can hold it")
                                    .endsWith("@replace-me.invalid");
                            assertThat(users.findByEmail(email).orElseThrow().getDisplayName())
                                    .describedAs("and the screen says so in words")
                                    .startsWith("PLACEHOLDER");
                        });
    }

    @Test
    @DisplayName("replacing a placeholder is an UPDATE to the row — the day-zero path in the runbook")
    void aPlaceholderIsReplacedByRepointingTheRow() {
        AppUser seeded = users.findByEmail(BREAK_GLASS_PLACEHOLDER).orElseThrow();

        seeded.changeEmail("Hostel.Office@example.com");
        users.saveAndFlush(seeded);

        assertThat(signIn.admit(new OidcIdentity("  hostel.office@example.com ", true)).user().getRole())
                .describedAs("the real manager now signs in, with the seeded row's role")
                .isEqualTo(UserRole.MANAGER);

        // Re-read before restoring: the sign-in above stamped `last_login_at` on the row, so the
        // copy held here is a version behind — the optimistic locking of section 7 in miniature.
        AppUser afterSignIn = users.findByEmail("hostel.office@example.com").orElseThrow();
        afterSignIn.changeEmail(BREAK_GLASS_PLACEHOLDER);
        users.saveAndFlush(afterSignIn);
    }
}
