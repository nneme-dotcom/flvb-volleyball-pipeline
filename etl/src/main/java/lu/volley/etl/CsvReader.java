package lu.volley.etl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 CSV reader.
 *
 * <p>Written by hand on purpose: the project has a single runtime dependency
 * (the JDBC driver) and the parser is small enough to test exhaustively.
 * Supports quoted fields, escaped quotes ({@code ""}), separators and line
 * breaks inside quotes, CRLF/LF line endings and a leading UTF-8 BOM.
 */
public final class CsvReader {

    private CsvReader() {
    }

    /** One parsed record together with the line it started on (for error reporting). */
    public record Row(int lineNo, List<String> fields) {
        /** Field by index, or an empty string when the row is shorter than expected. */
        public String get(int index) {
            return index < fields.size() ? fields.get(index) : "";
        }

        /** The record re-serialised as one CSV line; stored with rejected rows. */
        public String raw() {
            return CsvWriter.toLine(fields);
        }
    }

    public static List<Row> read(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(reader);
        }
    }

    public static List<Row> read(Reader source) throws IOException {
        // mark/reset is needed to look one character ahead after a quote
        Reader reader = source.markSupported() ? source : new BufferedReader(source);
        List<Row> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldWasQuoted = false;
        int lineNo = 1;          // physical line currently being read
        int recordStart = 1;     // line on which the current record started
        boolean first = true;

        int c;
        while ((c = reader.read()) != -1) {
            if (first) {
                first = false;
                if (c == '﻿') {
                    continue;    // skip byte-order mark
                }
            }
            if (inQuotes) {
                if (c == '"') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');           // escaped quote
                    } else {
                        inQuotes = false;            // closing quote
                        if (next != -1) {
                            reader.reset();
                        }
                    }
                } else {
                    if (c == '\n') {
                        lineNo++;
                    }
                    field.append((char) c);
                }
            } else if (c == '"' && field.length() == 0) {
                inQuotes = true;
                fieldWasQuoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                fieldWasQuoted = false;
            } else if (c == '\n') {
                // end of record; blank lines are skipped
                if (!fields.isEmpty() || field.length() > 0 || fieldWasQuoted) {
                    fields.add(field.toString());
                    rows.add(new Row(recordStart, List.copyOf(fields)));
                }
                fields.clear();
                field.setLength(0);
                fieldWasQuoted = false;
                lineNo++;
                recordStart = lineNo;
            } else if (c != '\r') {
                field.append((char) c);
            }
        }
        if (inQuotes) {
            throw new IOException("Unterminated quoted field starting on line " + recordStart);
        }
        if (!fields.isEmpty() || field.length() > 0 || fieldWasQuoted) {
            fields.add(field.toString());
            rows.add(new Row(recordStart, List.copyOf(fields)));
        }
        return rows;
    }
}
