package in.ac.iitm.hostel.duty.settings.infrastructure;

import in.ac.iitm.hostel.duty.settings.domain.Setting;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Manager-set overrides, keyed by property name ({@code uq_setting_name}). */
public interface SettingRepository extends JpaRepository<Setting, Long> {

    Optional<Setting> findByName(String name);
}
