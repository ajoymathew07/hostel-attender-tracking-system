package duty.ingestion.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which sheet to read, how often, and the credential to read it with.
 *
 * <p>All may be missing (e.g. on a developer machine); the app still starts and CSV upload still works.
 */
@ConfigurationProperties(prefix = "ingestion.sheets")
public class SheetsProperties {

    /** The response sheet's id; also its {@code source_ref}. */
    private String spreadsheetId = "";

    /** The tab name; Google Forms uses this by default. */
    private String sheetName = "Form Responses 1";

    /** Base64 service-account JSON, from {@code GOOGLE_SA_KEY}. */
    private String credentials = "";

    /** How often to poll. */
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

    /** Never prints the credential; Spring and Actuator may print this object. */
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
