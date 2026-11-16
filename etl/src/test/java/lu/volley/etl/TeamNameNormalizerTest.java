package lu.volley.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TeamNameNormalizerTest {

    private final TeamNameNormalizer normalizer = new TeamNameNormalizer(Map.of(
            "VCS", "VC Stroossen",
            "VB", "Volley Bartreng",
            "VBAL", "VB Amber-Lënster"));

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "VCS Hommes,          VC Stroossen 1",      // 2021/22: no squad number
            "VCS Hommes 2,        VC Stroossen 2",      // 2022-25
            "VCS 3 Hommes 3,      VC Stroossen 3",      // squad number repeated
            "VCS  Hommes 3,       VC Stroossen 3",      // double space in the source
            "VC Stroossen 1,      VC Stroossen 1",      // 2025 onwards
            "VC Stroossen,        VC Stroossen 1",      // official standings 2021/22
            "VB Hommes 1,         Volley Bartreng 1",   // code that is a prefix of another code
            "VB Amber -Lënster 1, VB Amber-Lënster 1",  // stray space before the hyphen
    })
    void everySpellingResolvesToTheSameTeam(String raw, String expected) {
        assertEquals(expected, normalizer.normalize(raw).orElseThrow().teamName());
    }

    @Test
    void placeholdersAndUnknownClubsAreNotResolved() {
        assertTrue(normalizer.normalize("#6, Meldeliste").isEmpty());
        assertTrue(normalizer.normalize("XYZ Hommes 1").isEmpty());
        assertTrue(normalizer.normalize("").isEmpty());
    }
}
