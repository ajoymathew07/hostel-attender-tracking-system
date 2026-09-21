package in.ac.iitm.hostel.duty.identity.infrastructure;

import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.domain.UserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Users and the Google allowlist (data model section 3.3).
 *
 * <p>Lookup is by normalised email, because a sign-in whose verified address differs only in case
 * or surrounding space must still admit the same person (test plan E20c).
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByEmailAndActiveIsTrue(String email);

    List<AppUser> findByRoleAndActiveIsTrueOrderByDisplayNameAsc(UserRole role);

    boolean existsByEmail(String email);
}
