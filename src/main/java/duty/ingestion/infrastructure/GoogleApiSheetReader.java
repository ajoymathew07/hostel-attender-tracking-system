package duty.ingestion.infrastructure;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@link SheetReader} over the real Sheets API v4 — the only class in the system that knows Google
 * exists.
 *
 * <p><strong>The client is built on first use, not at startup.</strong> The service-account key is
 * an environment variable and never a file in the repository (section 11.5), so a developer
 * machine and CI have none. Building the client eagerly would make the application refuse to start
 * without a credential it does not need to serve the manager's dashboard or the CSV fallback.
 * Nothing here runs until something actually asks to read the sheet, and the poller only asks when
 * {@link SheetsProperties#configured()}.
 *
 * <p>The scope is {@code spreadsheets.readonly}. If the key leaks, the blast radius is reading
 * complaints we already have (section 11.5).
 */
@Component
class GoogleApiSheetReader implements SheetReader {

    /** Sent as the User-Agent, so a quota question can be traced back to this application. */
    private static final String APPLICATION_NAME = "duty-tracker";

    private final SheetsProperties properties;

    /** Built once, on the first read. Volatile because the poller and "Sync Now" are two threads. */
    private volatile Sheets client;

    GoogleApiSheetReader(SheetsProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<List<Object>> readValues(String spreadsheetId, String range) {
        try {
            List<List<Object>> values =
                    client().spreadsheets().values().get(spreadsheetId, range).execute().getValues();
            // An empty tab comes back with no `values` member at all rather than an empty list.
            return values == null ? List.of() : values;
        } catch (Exception failed) {
            throw new SheetsUnavailableException(spreadsheetId, failed);
        }
    }

    private Sheets client() throws Exception {
        Sheets built = client;
        if (built == null) {
            built = build();
            client = built;
        }
        return built;
    }

    private Sheets build() throws Exception {
        byte[] json = Base64.getDecoder().decode(Objects.requireNonNull(properties.getCredentials()));
        GoogleCredentials credentials =
                GoogleCredentials.fromStream(new ByteArrayInputStream(json))
                        .createScoped(SheetsScopes.SPREADSHEETS_READONLY);
        return new Sheets.Builder(
                        GoogleNetHttpTransport.newTrustedTransport(),
                        GsonFactory.getDefaultInstance(),
                        new HttpCredentialsAdapter(credentials))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}
