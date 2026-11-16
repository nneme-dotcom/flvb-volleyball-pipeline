package lu.volley.etl;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** Writes RFC 4180 CSV (UTF-8, LF line endings, quotes only where needed). */
public final class CsvWriter implements AutoCloseable {

    private final BufferedWriter out;

    public CsvWriter(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
    }

    public void writeRow(List<String> fields) throws IOException {
        out.write(toLine(fields));
        out.write('\n');
    }

    static String toLine(List<String> fields) {
        return fields.stream().map(CsvWriter::escape).collect(Collectors.joining(","));
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        return needsQuotes ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
