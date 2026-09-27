package in.ac.iitm.hostel.duty.ingestion.domain;

/** Where a batch of rows came from (data model section 3.5). */
public enum SourceType {

    /** The Google Form response sheet, polled and trigger-driven (section 2.5). */
    GOOGLE_SHEETS,

    /** The offline fallback: a manager-uploaded CSV. */
    CSV_UPLOAD
}
