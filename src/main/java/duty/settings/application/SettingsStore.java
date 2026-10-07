package duty.settings.application;

import duty.audit.application.AuditTrail.AuditDetail;
import duty.audit.application.AuditTrail.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.application.CurrentUser;
import duty.settings.domain.Setting;
import duty.settings.infrastructure.SettingRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the manager's overrides.
 *
 * <p>No cache: the table is tiny, and a cache would go stale after an edit. Writing is manager-only;
 * reading is open because the dispatcher reads these during an unauthenticated poll.
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

    /** Saves a screenful of edits, auditing only the values that actually changed. */
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

    /** Audits one changed setting. */
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

    /**
     * A snapshot of the overrides in force. Every accessor falls back to the YAML value.
     *
     * <p>A snapshot so that all values used for one decision come from the same moment.
     */
    public record Settings(Map<String, String> values) {

        public String text(String name, String fallback) {
            return values.getOrDefault(name, fallback);
        }

        public double number(String name, double fallback) {
            String value = values.get(name);
            return value == null ? fallback : Double.parseDouble(value);
        }

        public int integer(String name, int fallback) {
            String value = values.get(name);
            return value == null ? fallback : Integer.parseInt(value);
        }

        /** ISO-8601, the same shape the YAML uses, so a copied value means the same thing. */
        public Duration duration(String name, Duration fallback) {
            String value = values.get(name);
            return value == null ? fallback : Duration.parse(value);
        }

        public LocalTime time(String name, LocalTime fallback) {
            String value = values.get(name);
            return value == null ? fallback : LocalTime.parse(value);
        }
    }
}
