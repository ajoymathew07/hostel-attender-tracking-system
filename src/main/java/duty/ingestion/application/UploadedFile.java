package duty.ingestion.application;

import java.util.Objects;

/** An uploaded file: name, media type and bytes, passed from the web layer to ingestion. */
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

    /** Name and type only, never the contents, which hold student details. */
    @Override
    public String toString() {
        return "UploadedFile[filename=" + filename + ", contentType=" + contentType + ", bytes=" + content.length + "]";
    }
}
