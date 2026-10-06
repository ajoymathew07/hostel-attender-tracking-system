package duty.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Upload limits (CSV only, 5 MB, 5000 rows), with type and size checked before parsing. */
class UploadLimitsTest {

    private static final UploadLimits LIMITS = UploadLimits.defaults();

    @Test
    @DisplayName("the design's numbers are the defaults: 5 MB and 5000 rows")
    void theDefaultsAreTheDesignsNumbers() {
        assertThat(LIMITS.maxBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(LIMITS.maxRows()).isEqualTo(5000);
    }

    @Test
    @DisplayName("an ordinary CSV upload passes")
    void anOrdinaryCsvPasses() {
        assertThatCode(() -> LIMITS.validate("responses.csv", "text/csv", 4096)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the media types a browser actually sends for a .csv are accepted")
    void theMediaTypesBrowsersSendAreAccepted() {
        for (String contentType :
                new String[] {
                    "text/csv", "text/csv; charset=UTF-8", "application/csv", "text/plain",
                    "application/vnd.ms-excel"
                }) {
            assertThatCode(() -> LIMITS.validate("responses.csv", contentType, 10))
                    .as(contentType)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a non-CSV content type is refused before anything is parsed")
    void aNonCsvContentTypeIsRefused() {
        assertThatThrownBy(() -> LIMITS.validate("payload.pdf", "application/pdf", 512))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("CSV");
    }

    @Test
    @DisplayName("a CSV content type on a file that is not a .csv is still refused")
    void theExtensionMustAgreeWithTheContentType() {
        assertThatThrownBy(() -> LIMITS.validate("payload.xlsx", "text/csv", 512))
                .isInstanceOf(UploadRejectedException.class);
        assertThatThrownBy(() -> LIMITS.validate(null, "text/csv", 512))
                .isInstanceOf(UploadRejectedException.class);
    }

    @Test
    @DisplayName("a file over 5 MB is refused on its length alone")
    void anOversizeFileIsRefused() {
        assertThatThrownBy(() -> LIMITS.validate("responses.csv", "text/csv", 5L * 1024 * 1024 + 1))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("5");
    }

    @Test
    @DisplayName("an empty file is refused rather than producing an empty, successful run")
    void anEmptyFileIsRefused() {
        assertThatThrownBy(() -> LIMITS.validate("responses.csv", "text/csv", 0))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("a missing content type is refused — we do not guess at an unlabelled upload")
    void aMissingContentTypeIsRefused() {
        assertThatThrownBy(() -> LIMITS.validate("responses.csv", null, 512))
                .isInstanceOf(UploadRejectedException.class);
    }

    @Test
    @DisplayName("5000 rows is allowed; 5001 is not")
    void theRowLimitIsInclusive() {
        assertThatCode(() -> LIMITS.requireWithinRowLimit(5000)).doesNotThrowAnyException();
        assertThatThrownBy(() -> LIMITS.requireWithinRowLimit(5001))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("5000");
    }

    @Test
    @DisplayName("the limits are configuration, not constants")
    void theLimitsAreConfigurable() {
        UploadLimits tighter = new UploadLimits(1024, 10);

        assertThatThrownBy(() -> tighter.validate("responses.csv", "text/csv", 2048))
                .isInstanceOf(UploadRejectedException.class);
        assertThatThrownBy(() -> tighter.requireWithinRowLimit(11)).isInstanceOf(UploadRejectedException.class);
        assertThatThrownBy(() -> new UploadLimits(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UploadLimits(1024, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
