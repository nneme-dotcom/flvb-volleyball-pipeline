package lu.volley.etl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one raw CSV row into a validated {@link MatchRecord}.
 *
 * <p>Hard errors (the row cannot be trusted) raise a {@link RejectException};
 * soft problems (one field is implausible but the match is usable) are
 * reported through the warning callback and the field is set to null.
 */
public final class MatchTransformer {

    /** Column order of data/raw/flvb_men_matches.csv. */
    public static final List<String> HEADER = List.of("season", "league", "date", "time", "match_no",
            "home_team", "away_team", "result", "set_scores", "duration_min", "venue", "match_uuid");

    private static final int SEASON = 0, LEAGUE = 1, DATE = 2, TIME = 3, MATCH_NO = 4, HOME = 5, AWAY = 6,
            RESULT = 7, SET_SCORES = 8, DURATION = 9, VENUE = 10, UUID = 11;

    private static final DateTimeFormatter SOURCE_DATE =
            DateTimeFormatter.ofPattern("dd.MM.uu").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern SEASON_LABEL = Pattern.compile("^(\\d{4})/(\\d{4})$");
    private static final Pattern RESULT_FORMAT = Pattern.compile("^([0-3]):([0-3])$");
    private static final Pattern UUID_FORMAT = Pattern.compile("^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$");
    /** Longest plausible match; anything above is a data-entry error in the source. */
    static final int MAX_DURATION_MIN = 300;

    private final TeamNameNormalizer teams;
    private final Set<String> knownLeagues;

    public MatchTransformer(TeamNameNormalizer teams, Set<String> knownLeagues) {
        this.teams = teams;
        this.knownLeagues = knownLeagues;
    }

    /**
     * @param row     raw CSV row
     * @param warning callback receiving (reason code, detail) for soft problems
     */
    public MatchRecord transform(CsvReader.Row row, BiConsumer<String, String> warning) throws RejectException {
        if (row.fields().size() != HEADER.size()) {
            throw new RejectException("WRONG_COLUMN_COUNT",
                    "Expected " + HEADER.size() + " columns, found " + row.fields().size());
        }

        String uuid = row.get(UUID).trim().toLowerCase();
        if (!UUID_FORMAT.matcher(uuid).matches()) {
            throw new RejectException("INVALID_MATCH_ID", "Not a UUID: '" + row.get(UUID) + "'");
        }

        String season = row.get(SEASON).trim();
        Matcher seasonMatcher = SEASON_LABEL.matcher(season);
        if (!seasonMatcher.matches()
                || Integer.parseInt(seasonMatcher.group(2)) != Integer.parseInt(seasonMatcher.group(1)) + 1) {
            throw new RejectException("INVALID_SEASON", "Season must look like 2025/2026: '" + season + "'");
        }
        int startYear = Integer.parseInt(seasonMatcher.group(1));

        String league = row.get(LEAGUE).trim();
        if (!knownLeagues.contains(league)) {
            throw new RejectException("UNKNOWN_LEAGUE", "League '" + league + "' is not in leagues.csv");
        }

        LocalDate date;
        try {
            date = LocalDate.parse(row.get(DATE).trim(), SOURCE_DATE);
        } catch (DateTimeParseException e) {
            throw new RejectException("INVALID_DATE", "Expected dd.MM.yy: '" + row.get(DATE) + "'");
        }
        // A season runs from summer to summer; a date outside it means the row is mislabelled.
        if (date.isBefore(LocalDate.of(startYear, 7, 1)) || date.isAfter(LocalDate.of(startYear + 1, 8, 31))) {
            throw new RejectException("DATE_OUTSIDE_SEASON", date + " is not within season " + season);
        }

        int matchNo;
        try {
            matchNo = Integer.parseInt(row.get(MATCH_NO).trim());
        } catch (NumberFormatException e) {
            throw new RejectException("INVALID_MATCH_NO", "Not a number: '" + row.get(MATCH_NO) + "'");
        }

        TeamNameNormalizer.TeamRef home = resolveTeam(row.get(HOME));
        TeamNameNormalizer.TeamRef away = resolveTeam(row.get(AWAY));
        if (home.equals(away)) {
            throw new RejectException("SAME_TEAM", "Home and away are both " + home.teamName());
        }

        String result = row.get(RESULT).trim();
        boolean played = !result.isEmpty();
        Integer homeSets = null;
        Integer awaySets = null;
        List<SetScore> sets = List.of();
        boolean forfeit = false;
        if (played) {
            Matcher resultMatcher = RESULT_FORMAT.matcher(result);
            if (!resultMatcher.matches()) {
                throw new RejectException("INVALID_RESULT", "Expected e.g. 3:1, found '" + result + "'");
            }
            homeSets = Integer.parseInt(resultMatcher.group(1));
            awaySets = Integer.parseInt(resultMatcher.group(2));
            if ((homeSets == 3) == (awaySets == 3)) {
                throw new RejectException("INVALID_RESULT", "Exactly one team must win 3 sets: '" + result + "'");
            }
            sets = SetScore.parseAll(row.get(SET_SCORES));
            // Cross-check: the set scores must add up to the match result.
            long homeSetsFromScores = sets.stream().filter(SetScore::homeWon).count();
            if (sets.size() != homeSets + awaySets || homeSetsFromScores != homeSets) {
                throw new RejectException("RESULT_SET_MISMATCH",
                        "Result " + result + " does not match set scores '" + row.get(SET_SCORES) + "'");
            }
            forfeit = sets.stream().allMatch(SetScore::isForfeitScore);
        }

        // 00:00 is the source's placeholder for "time not fixed yet".
        LocalTime time = null;
        String rawTime = row.get(TIME).trim();
        if (!rawTime.isEmpty() && !rawTime.equals("00:00")) {
            try {
                time = LocalTime.parse(rawTime);
            } catch (DateTimeParseException e) {
                warning.accept("INVALID_TIME", "Time ignored: '" + rawTime + "'");
            }
        }

        Integer duration = null;
        String rawDuration = row.get(DURATION).trim();
        if (!rawDuration.isEmpty()) {
            try {
                int minutes = Integer.parseInt(rawDuration);
                if (minutes >= 1 && minutes <= MAX_DURATION_MIN) {
                    duration = minutes;
                } else {
                    warning.accept("DURATION_OUT_OF_RANGE", "Duration " + minutes + " min set to NULL");
                }
            } catch (NumberFormatException e) {
                warning.accept("DURATION_OUT_OF_RANGE", "Duration '" + rawDuration + "' set to NULL");
            }
        }

        String venue = row.get(VENUE).trim().replaceAll("\\s+", " ");
        return new MatchRecord(row.lineNo(), uuid, season, startYear, league, matchNo, date, time, home, away,
                played, homeSets, awaySets, sets, duration, forfeit, venue.isEmpty() ? null : venue);
    }

    private TeamNameNormalizer.TeamRef resolveTeam(String rawName) throws RejectException {
        return teams.normalize(rawName).orElseThrow(() ->
                new RejectException("UNKNOWN_TEAM", "'" + rawName + "' does not match any club in clubs.csv"));
    }
}
