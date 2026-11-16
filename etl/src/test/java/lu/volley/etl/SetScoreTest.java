package lu.volley.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SetScoreTest {

    @ParameterizedTest(name = "set {0}: {1}-{2} valid={3}")
    @CsvSource({
            "1, 25, 23, true",
            "1, 27, 25, true",     // extra points, won by two
            "1, 25,  0, true",     // forfeit
            "5, 15, 13, true",     // tie-break is played to 15
            "5, 16, 14, true",
            "1, 25, 24, false",    // not two clear points
            "1, 24, 22, false",    // nobody reached 25
            "1, 28, 25, false",    // play would have stopped at 27-25
            "5, 25, 23, true",     // a long tie-break is still won by two
            "4, 15, 13, false",    // only set 5 is short
    })
    void validatesAgainstScoringRules(int setNo, int home, int away, boolean valid) {
        assertEquals(valid, new SetScore(setNo, home, away).isValid());
    }

    @Test
    void parsesSourceFormatAndNumbersTheSets() throws RejectException {
        List<SetScore> sets = SetScore.parseAll("16:25 25:21 16:25 25:19 16:14");
        assertEquals(5, sets.size());
        assertEquals(new SetScore(5, 16, 14), sets.get(4));
        assertFalse(sets.get(0).homeWon());
        assertTrue(sets.get(4).homeWon());
    }

    @Test
    void rejectsMalformedOrImpossibleScores() {
        assertEquals("INVALID_SET_SCORE", assertThrows(RejectException.class, () -> SetScore.parseAll("25-20")).reasonCode());
        assertThrows(RejectException.class, () -> SetScore.parseAll("25:x"));
        assertThrows(RejectException.class, () -> SetScore.parseAll("25:24"));
    }

    @Test
    void detectsForfeitScores() {
        assertTrue(new SetScore(1, 0, 25).isForfeitScore());
        assertFalse(new SetScore(1, 25, 1).isForfeitScore());
    }
}
