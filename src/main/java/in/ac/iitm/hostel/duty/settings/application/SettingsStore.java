package in.ac.iitm.hostel.duty.settings.application;

import in.ac.iitm.hostel.duty.audit.application.AuditDetail;
import in.ac.iitm.hostel.duty.audit.application.AuditEntry;
import in.ac.iitm.hostel.duty.audit.application.AuditTrail;
import in.ac.iitm.hostel.duty.identity.application.CurrentUser;
import in.ac.iitm.hostel.duty.settings.domain.Setting;
import in.ac.iitm.hostel.duty.settings.infrastructure.SettingRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and writing the manager's overrides.
 *
 * <p><strong>Read-through, with no cache.</strong> A cached copy would be a second source of truth
 * for a value the design calls configuration, and the standby jar of architecture section 2.6 runs
 * against the same database from a different process — a cache in one of them would not see the
 * other's edit until a restart. The whole table is a handful of rows read once per complaint, so
 * there is nothing here worth caching.
 *
 * <p>Writing is manager-only, at the service layer (section 11.3). Reading is not: the dispatcher
 * reads these during an unauthenticated poll, and the values are thresholds rather than anyone's
 * data.
 */
@Service
@Transactional(readOnly = true)
public class SettingsStore {

    private static final String ENTITY_TYPE = "app_setting";

    private final SettingRepository settings;
    private final AuditTrail audit;

    SettingsStore(SettingRepository settings, AuditTrail audit) {
        this.settings = settings;
        this.audit = audit;
    }

    /** Every override in force. A key that is absent means "whatever the YAML says". */
    public Settings current() {
        Map<String, String> values = new LinkedHashMap<>();
        settings.findAll().forEach(setting -> values.put(setting.getName(), setting.getValue()));
        return new Settings(values);
    }

    /**
     * Applies a screenful of edits, and audits each value that actually moved.
     *
     * <p>A value equal to what is already stored writes nothing. The audit trail is read to settle
     * disputes about what the system was configured to do at a given moment (section 11.7), and a
     * row per unchanged field on every save would bury the changes that matter.
     */
    @Transactional
    @PreAuthorize("hasRole('MANAGER')")
    public void save(Map<String, String> edits) {
        Instant now = Instant.now();
        edits.forEach((name, value) -> apply(name, value, now));
    }

    private void apply(String name, String value, Instant now) {
        Setting existing = settings.findByName(name).orElse(null);
        if (existing == null) {
            Setting created =
                    settings.saveAndFlush(new Setting(name, value, now, CurrentUser.idOrNull()));
            record(created, null, value, now);
            return;
        }
        if (existing.getValue().equals(value)) {
            return;
        }
        String previous = existing.getValue();
        existing.changeTo(value, now, CurrentUser.idOrNull());
        record(existing, previous, value, now);
    }

    /** Thresholds and weights, never anyone's data — safe to keep indefinitely (section 11.4). */
    private void record(Setting setting, String from, String to, Instant now) {
        audit.record(
                new AuditEntry(
                        now,
                        CurrentUser.idOrNull(),
                        CurrentUser.roleOrNull(),
                        ENTITY_TYPE,
                        setting.getId(),
                        "SETTING_CHANGED",
                        from,
                        to,
                        AuditDetail.of("name", setting.getName()).json(),
                        null));
    }
}
