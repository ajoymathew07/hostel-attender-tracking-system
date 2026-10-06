package duty.identity.infrastructure;

import duty.identity.domain.AppUser;
import duty.identity.domain.UserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Users and the Google allowlist. Lookups use the normalised email. */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByEmailAndActiveIsTrue(String email);

    List<AppUser> findByRoleAndActiveIsTrueOrderByDisplayNameAsc(UserRole role);

    boolean existsByEmail(String email);

    /** How many active users have this role; used to keep at least one manager. */
    long countByRoleAndActiveIsTrue(UserRole role);
}
