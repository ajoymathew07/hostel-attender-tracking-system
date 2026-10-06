package duty.ingestion.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which sheet to poll, how often, and the credential to poll it with (sections 2.5, 5.3, 11.5).
 *
 * <p>The column mapping is deliberately <em>not</em> here: it lives at {@code ingestion.columns}
 * and is shared with the CSV fallback, which is a download of this very sheet and so has these
 * very headers (see {@link IngestionProperties}).
 *
 * <p><strong>Every one of these may be absent.</strong> The service-account key is an environment
 * variable and never a file in the repository (section 11.5), so a developer machine and CI have
 * none. {@link #configured()} is how the poller finds that out; the application starts either way
 * and the CSV path keeps working, which is the whole point of having two sources behind one port.
 */
@ConfigurationProperties(prefix = "ingestion.sheets")
public class SheetsProperties {

    /** The response sheet's id, which is also this adapter's {@code source_ref}. */
    private String spreadsheetId = "";

    /** The tab within it. Google Forms names the response tab this by default. */
    private String sheetName = "Form Responses 1";

    /** Base64 service-account JSON, from {@code GOOGLE_SA_KEY} (section 11.5). */
    private String credentials = "";

    /** Section 5.3's poll rate: one read per three minutes is ~0.1% of the API quota. */
    private Duration pollInterval = Duration.ofMinutes(3);

    /** Whether there is both a sheet to read and a credential to read it with. */
    public boolean configured() {
        return !spreadsheetId.isBlank() && !credentials.isBlank();
    }

    public String getSpreadsheetId() {
        return spreadsheetId;
    }

    public void setSpreadsheetId(String spreadsheetId) {
        this.spreadsheetId = spreadsheetId;
    }

    public String getSheetName() {
        return sheetName;
    }

    public void setSheetName(String sheetName) {
        this.sheetName = sheetName;
    }

    public String getCredentials() {
        return credentials;
    }

    public void setCredentials(String credentials) {
        this.credentials = credentials;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    /**
     * Never the credential.
     *
     * <p>{@code @ConfigurationProperties} objects are printed by Actuator's {@code /configprops}
     * and by Spring's own binding diagnostics, and this one holds a private key (section 11.5).
     */
    @Override
    public String toString() {
        return "SheetsProperties[spreadsheetId="
                + spreadsheetId
                + ", sheetName="
                + sheetName
                + ", credentials="
                + (credentials.isBlank() ? "absent" : "present")
                + "]";
    }
}
