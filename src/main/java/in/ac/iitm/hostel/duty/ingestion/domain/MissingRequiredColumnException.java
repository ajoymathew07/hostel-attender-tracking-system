package in.ac.iitm.hostel.duty.ingestion.domain;

import java.util.List;

/**
 * A required column could not be resolved from the source's headers (section 5.2).
 *
 * <p>This fails the <strong>whole run</strong> rather than the row. A silent partial ingest is far
 * worse than a visible failure: the manager would have no way to know complaints were being
 * dropped, and would find out only when a resident asked why nothing had happened.
 *
 * <p>The message names the field and the headers we would have accepted, because that is exactly
 * what the manager needs in order to fix the Form. It contains no cell values, so it is safe in a
 * log and in {@code ingestion_run.error_detail} (section 11.4).
 */
public class MissingRequiredColumnException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient SourceField field;

    public MissingRequiredColumnException(SourceField field, List<String> acceptedAliases) {
        super(
                "required column '"
                        + field.configKey()
                        + "' was not found; accepted headers are "
                        + String.join(", ", acceptedAliases));
        this.field = field;
    }

    public SourceField getField() {
        return field;
    }
}
