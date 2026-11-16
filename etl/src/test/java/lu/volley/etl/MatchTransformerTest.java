package lu.volley.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MatchTransformerTest {

    private static final String UUID = "bfdb9945-ffd2-42a9-9f5d-3e9b17142bfe";

    private final MatchTransformer transformer = new MatchTransformer(
            new TeamNameNormalizer(Map.of("VCL", "VC Lorentzweiler", "VCB", "VC Belair")),
            Set.of("MEN_DN"));
    private final List<String> warnings = new ArrayList<>();

    /** A valid played match; individual tests override single columns. */
    private static String[] validRow() {
        return new String[] {"2026/2027", "MEN_DN", "24.10.26", "20:00", "1", "VC Lorentzweiler 1", "VC Belair 1",
                "3:0", "25:17 25:15 25:21", "78", "", UUID};
    }

    private MatchRecord transform(String[] fields) throws RejectException {
        return transformer.transform(new CsvReader.Row(2, Arrays.asList(fields)), (code, detail) -> warnings.add(code));
    }

    private String rejectReason(int column, String value) {
        String[] row = validRow();
        row[column] = value;
        return assertThrows(RejectException.class, () -> transform(row)).reasonCode();
    }

    @Test
    void transformsPlayedMatch() throws RejectException {
        MatchRecord match = transform(validRow());
        assertEquals(LocalDate.of(2026, 10, 24), match.date());
        assertEquals(LocalTime.of(20, 0), match.time());
        assertEquals(2026, match.seasonStartYear());
        assertEquals("VC Lorentzweiler 1", match.home().teamName());
        assertTrue(match.played());
        assertEquals(3, match.homeSets());
        assertEquals(0, match.awaySets());
        assertEquals(3, match.sets().size());
        assertEquals(78, match.durationMin());
        assertFalse(match.forfeit());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void transformsScheduledMatchWithPlaceholderTime() throws RejectException {
        String[] row = validRow();
        row[3] = "00:00";
        row[7] = "";
        row[8] = "";
        row[9] = "";
        row[10] = "Hall Sportif  Belair";
        MatchRecord match = transform(row);
        assertFalse(match.played());
        assertNull(match.time(), "00:00 means the time is not fixed yet");
        assertNull(match.homeSets());
        assertTrue(match.sets().isEmpty());
        assertEquals("Hall Sportif Belair", match.venue());
    }

    @Test
    void flagsForfeits() throws RejectException {
        String[] row = validRow();
        row[8] = "25:0 25:0 25:0";
        assertTrue(transform(row).forfeit());
    }

    @Test
    void implausibleDurationBecomesNullWithWarning() throws RejectException {
        String[] row = validRow();
        row[9] = "18885";
        assertNull(transform(row).durationMin());
        assertEquals(List.of("DURATION_OUT_OF_RANGE"), warnings);
    }

    @Test
    void rejectsBrokenRows() {
        assertEquals("INVALID_DATE", rejectReason(2, "Postponed"));
        assertEquals("INVALID_DATE", rejectReason(2, "31.02.27"));
        assertEquals("DATE_OUTSIDE_SEASON", rejectReason(2, "24.10.24"));
        assertEquals("INVALID_SEASON", rejectReason(0, "2026/2028"));
        assertEquals("UNKNOWN_LEAGUE", rejectReason(1, "WOMEN_DN"));
        assertEquals("UNKNOWN_TEAM", rejectReason(5, "#6, Meldeliste"));
        assertEquals("SAME_TEAM", rejectReason(6, "VCL Hommes 1"));
        assertEquals("INVALID_RESULT", rejectReason(7, "2:2"));
        assertEquals("RESULT_SET_MISMATCH", rejectReason(7, "3:1"));
        assertEquals("INVALID_SET_SCORE", rejectReason(8, "25:17 25:15 25:24"));
        assertEquals("INVALID_MATCH_ID", rejectReason(11, "not-a-uuid"));
    }

    @Test
    void rejectsRowsWithMissingColumns() {
        CsvReader.Row shortRow = new CsvReader.Row(3, List.of("2026/2027", "MEN_DN"));
        assertEquals("WRONG_COLUMN_COUNT",
                assertThrows(RejectException.class, () -> transformer.transform(shortRow, (c, d) -> { })).reasonCode());
    }
}
