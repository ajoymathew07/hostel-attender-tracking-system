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
 * {@link SheetReader} using the real Google Sheets API.
 *
 * <p>The client is built on first use, not at startup, so the app still starts without a credential
 * (as on a developer machine or in CI). The scope is read-only.
 */
@Component
class GoogleApiSheetReader implements SheetReader {

    /** Sent as the User-Agent. */
    private static final String APPLICATION_NAME = "duty-tracker";

    private final SheetsProperties properties;

    /** Built on first read. Volatile because the poller and "Sync Now" run on different threads. */
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
