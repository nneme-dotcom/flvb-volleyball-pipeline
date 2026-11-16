package lu.volley.etl;

import java.util.ArrayList;
import java.util.List;

/** Points scored by each side in one set. */
public record SetScore(int setNo, int homePoints, int awayPoints) {

    public boolean homeWon() {
        return homePoints > awayPoints;
    }

    /** A set awarded 25-0 (or 15-0) without being played. */
    public boolean isForfeitScore() {
        return Math.min(homePoints, awayPoints) == 0 && Math.max(homePoints, awayPoints) == target();
    }

    private int target() {
        return setNo == 5 ? 15 : 25;
    }

    /**
     * Checks the score against the rules of volleyball: sets are played to 25
     * (the fifth to 15) and must be won by two clear points, so a score above
     * the target is only possible with a margin of exactly two.
     */
    public boolean isValid() {
        int high = Math.max(homePoints, awayPoints);
        int low = Math.min(homePoints, awayPoints);
        if (high < target() || high - low < 2) {
            return false;
        }
        return high == target() || high - low == 2;
    }

    /**
     * Parses the source format {@code "25:19 25:14 25:11"}.
     *
     * @throws RejectException if the text is malformed or a score breaks the rules
     */
    public static List<SetScore> parseAll(String text) throws RejectException {
        List<SetScore> sets = new ArrayList<>();
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return sets;
        }
        int setNo = 1;
        for (String token : trimmed.split("\\s+")) {
            String[] parts = token.split(":");
            SetScore set;
            try {
                if (parts.length != 2) {
                    throw new NumberFormatException();
                }
                set = new SetScore(setNo, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            } catch (NumberFormatException e) {
                throw new RejectException("INVALID_SET_SCORE", "Cannot parse set score '" + token + "'");
            }
            if (!set.isValid()) {
                throw new RejectException("INVALID_SET_SCORE", "Set " + setNo + " score " + token + " breaks the scoring rules");
            }
            sets.add(set);
            setNo++;
        }
        return sets;
    }
}
