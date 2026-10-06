package duty.ingestion.infrastructure;

import duty.ingestion.domain.UploadLimits;
import duty.ingestion.domain.UploadRejectedException;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal RFC 4180 CSV reader: quoted cells, embedded commas and newlines, doubled quotes.
 *
 * <p>Hand-written because the only input is a Google Sheets export; a library would add features and a
 * dependency for nothing. The row limit is enforced while reading.
 */
final class CsvReader {

    private static final char QUOTE = '"';
    private static final char COMMA = ',';
    private static final char BOM = '﻿';

    private CsvReader() {}

    /** @throws UploadRejectedException if the file is empty or has too many rows */
    static CsvContent read(String text, UploadLimits limits) {
        List<List<String>> records = parse(text, limits.maxRows());
        if (records.isEmpty()) {
            throw new UploadRejectedException("That file has no header row");
        }
        return new CsvContent(records.get(0), records.subList(1, records.size()));
    }

    private static List<List<String>> parse(String text, int maxRows) {
        String content = stripBom(text);

        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        int index = 0;
        while (index < content.length()) {
            char character = content.charAt(index);

            if (quoted) {
                if (character == QUOTE) {
                    // A doubled quote inside a quoted cell is one literal quote.
                    if (index + 1 < content.length() && content.charAt(index + 1) == QUOTE) {
                        cell.append(QUOTE);
                        index += 2;
                        continue;
                    }
                    quoted = false;
                    index++;
                    continue;
                }
                cell.append(character);
                index++;
                continue;
            }

            switch (character) {
                case QUOTE -> {
                    quoted = true;
                    index++;
                }
                case COMMA -> {
                    record.add(cell.toString());
                    cell.setLength(0);
                    index++;
                }
                case '\r' -> index++;
                case '\n' -> {
                    record.add(cell.toString());
                    cell.setLength(0);
                    index++;
                    addRecord(records, record, maxRows);
                    record = new ArrayList<>();
                }
                default -> {
                    cell.append(character);
                    index++;
                }
            }
        }

        // A final line with no trailing newline is still a record.
        if (cell.length() > 0 || !record.isEmpty()) {
            record.add(cell.toString());
            addRecord(records, record, maxRows);
        }
        return records;
    }

    /** Adds a record unless every cell is blank, so blank lines do not become empty complaints. */
    private static void addRecord(List<List<String>> records, List<String> record, int maxRows) {
        boolean blank = record.stream().allMatch(cell -> cell == null || cell.isBlank());
        if (blank) {
            return;
        }
        records.add(List.copyOf(record));
        // records includes the header row, so the data-row count is one less.
        if (records.size() - 1 > maxRows) {
            throw new UploadRejectedException(
                    "That file holds more than the " + maxRows + " rows one upload may carry");
        }
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }

    /** A parsed file: its header row, and the data rows under it. */
    record CsvContent(List<String> headers, List<List<String>> rows) {}
}
