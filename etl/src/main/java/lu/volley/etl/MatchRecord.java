package lu.volley.etl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * A validated, typed match ready to be loaded. Nullable fields are optional in
 * the source: scheduled matches have no score, played matches have no venue.
 */
public record MatchRecord(
        int lineNo,
        String matchUuid,
        String season,
        int seasonStartYear,
        String leagueCode,
        int matchNo,
        LocalDate date,
        LocalTime time,               // null when not yet fixed
        TeamNameNormalizer.TeamRef home,
        TeamNameNormalizer.TeamRef away,
        boolean played,
        Integer homeSets,             // null when scheduled
        Integer awaySets,
        List<SetScore> sets,
        Integer durationMin,          // null when unknown
        boolean forfeit,
        String venue) {               // null when unknown
}
