package duty.ingestion.domain;

import java.util.List;

/**
 * A required column is missing from the headers. Fails the whole run, so the manager notices instead
 * of complaints silently going missing.
 *
 * <p>The message names the field and the accepted header names, so the Form can be fixed.
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
