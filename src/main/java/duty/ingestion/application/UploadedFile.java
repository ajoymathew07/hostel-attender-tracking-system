package duty.ingestion.application;

import java.util.Objects;

/**
 * An uploaded file, handed from the web layer to ingestion.
 *
 * <p>Carries the three things section 11.6 validates before parsing — name, media type and length
 * — alongside the bytes, so the check and the content cannot drift apart.
 */
public record UploadedFile(String filename, String contentType, byte[] content) {

    public UploadedFile {
        Objects.requireNonNull(content, "content");
        content = content.clone();
    }

    public long size() {
        return content.length;
    }

    public byte[] content() {
        return content.clone();
    }

    /**
     * The name and type, never the bytes.
     *
     * <p>A record's generated {@code toString} would print the byte array's identity, which is
     * useless, and any future change to hold the decoded text would print the students' details
     * straight into a log (section 11.4).
     */
    @Override
    public String toString() {
        return "UploadedFile[filename=" + filename + ", contentType=" + contentType + ", bytes=" + content.length + "]";
    }
}
