package lu.volley.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvReaderTest {

    private static List<CsvReader.Row> parse(String csv) throws IOException {
        return CsvReader.read(new StringReader(csv));
    }

    @Test
    void readsPlainFieldsAndKeepsEmptyOnes() throws IOException {
        List<CsvReader.Row> rows = parse("a,b,c\n1,,3\n");
        assertEquals(2, rows.size());
        assertEquals(List.of("1", "", "3"), rows.get(1).fields());
        assertEquals(2, rows.get(1).lineNo());
    }

    @Test
    void handlesQuotedSeparatorsQuotesAndLineBreaks() throws IOException {
        List<CsvReader.Row> rows = parse("\"Centre Sportif \"\"Holleschbierg\"\" Hesperange\",\"1,268\",\"two\nlines\"\nnext,row,here");
        assertEquals("Centre Sportif \"Holleschbierg\" Hesperange", rows.get(0).get(0));
        assertEquals("1,268", rows.get(0).get(1));
        assertEquals("two\nlines", rows.get(0).get(2));
        assertEquals(3, rows.get(1).lineNo(), "line numbers account for embedded line breaks");
    }

    @Test
    void toleratesBomCrlfAndBlankLines() throws IOException {
        List<CsvReader.Row> rows = parse("﻿a,b\r\n\r\n1,2\r\n");
        assertEquals(2, rows.size());
        assertEquals("a", rows.get(0).get(0));
        assertEquals(List.of("1", "2"), rows.get(1).fields());
    }

    @Test
    void rejectsUnterminatedQuote() {
        assertThrows(IOException.class, () -> parse("a,\"b\n"));
    }

    @Test
    void writerOutputIsReadBackUnchanged() throws IOException {
        List<String> fields = List.of("plain", "with,comma", "with \"quote\"", "");
        assertEquals(fields, parse(CsvWriter.toLine(fields)).get(0).fields());
    }
}
