package lu.volley.etl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The ETL job: extract the raw CSV files, validate and normalise each row,
 * load the relational model and build the analytical views.
 *
 * <p>The load is a full refresh executed in a single transaction: either the
 * whole database is rebuilt or, on any error, nothing changes. Re-running the
 * job on the same input always produces the same database (idempotent).
 */
public final class Pipeline {

    static final String MATCH_FILE = "raw/flvb_men_matches.csv";
    static final String STANDINGS_FILE = "raw/flvb_men_standings_official.csv";
    static final String CLUBS_FILE = "reference/clubs.csv";
    static final String LEAGUES_FILE = "reference/leagues.csv";

    /** Summary of one run, also written to {@code etl_run}. */
    public record Result(int rowsRead, int matchesLoaded, int setsLoaded, int rejected, int warnings) {
    }

    private final Path dataDir;
    private final Path sqlDir;
    private final Connection db;

    // surrogate keys handed out during the load
    private final Map<String, Integer> seasonIds = new HashMap<>();
    private final Map<String, Integer> leagueIds = new HashMap<>();
    private final Map<String, Integer> clubIds = new HashMap<>();
    private final Map<String, Integer> teamIds = new LinkedHashMap<>();
    private final Map<String, Integer> venueIds = new LinkedHashMap<>();

    private int rejected;
    private int warnings;

    public Pipeline(Path dataDir, Path sqlDir, Connection db) {
        this.dataDir = dataDir;
        this.sqlDir = sqlDir;
        this.db = db;
    }

    public Result run() throws IOException, SQLException {
        String startedAt = now();
        db.setAutoCommit(false);
        try {
            Database.runScript(db, sqlDir.resolve("01_schema.sql"));

            // ---- reference data -------------------------------------------------
            Map<String, String> clubs = loadClubs();
            loadLeagues();
            MatchTransformer transformer =
                    new MatchTransformer(new TeamNameNormalizer(clubs), leagueIds.keySet());

            // ---- extract --------------------------------------------------------
            List<CsvReader.Row> rows = CsvReader.read(dataDir.resolve(MATCH_FILE));
            if (rows.isEmpty() || !rows.get(0).fields().equals(MatchTransformer.HEADER)) {
                throw new IOException(MATCH_FILE + ": unexpected header, expected " + MatchTransformer.HEADER);
            }
            rows = rows.subList(1, rows.size());
            stageRawRows(rows);

            // ---- transform ------------------------------------------------------
            List<MatchRecord> matches = new ArrayList<>();
            Set<String> seenUuids = new HashSet<>();
            Set<String> seenMatchNos = new HashSet<>();
            for (CsvReader.Row row : rows) {
                try {
                    MatchRecord match = transformer.transform(row,
                            (code, detail) -> log(MATCH_FILE, row, "WARNING", code, detail));
                    // The source lists some matches under two divisions; the match id
                    // is the natural key, so only the first occurrence is kept.
                    if (!seenUuids.add(match.matchUuid())) {
                        throw new RejectException("DUPLICATE_MATCH", "Match id already loaded: " + match.matchUuid());
                    }
                    if (!seenMatchNos.add(match.season() + "|" + match.leagueCode() + "|" + match.matchNo())) {
                        throw new RejectException("DUPLICATE_MATCH_NO", "Match number " + match.matchNo()
                                + " used twice in " + match.season() + " " + match.leagueCode());
                    }
                    matches.add(match);
                } catch (RejectException e) {
                    log(MATCH_FILE, row, "REJECT", e.reasonCode(), e.getMessage());
                }
            }

            // ---- load -----------------------------------------------------------
            loadSeasons(matches);
            int sets = loadMatches(matches);
            loadOfficialStandings(new TeamNameNormalizer(clubs));
            Database.runScript(db, sqlDir.resolve("02_views.sql"));

            Result result = new Result(rows.size(), matches.size(), sets, rejected, warnings);
            recordRun(startedAt, result);
            db.commit();
            return result;
        } catch (IOException | SQLException | RuntimeException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    // ------------------------------------------------------------------ reference

    private Map<String, String> loadClubs() throws IOException, SQLException {
        Map<String, String> clubs = new LinkedHashMap<>();
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO club (club_id, club_code, club_name) VALUES (?, ?, ?)")) {
            int id = 0;
            for (CsvReader.Row row : dataRows(CLUBS_FILE)) {
                clubs.put(row.get(0).trim(), row.get(1).trim());
                clubIds.put(row.get(0).trim(), ++id);
                ps.setInt(1, id);
                ps.setString(2, row.get(0).trim());
                ps.setString(3, row.get(1).trim());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return clubs;
    }

    private void loadLeagues() throws IOException, SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO league (league_id, league_code, league_name, tier) VALUES (?, ?, ?, ?)")) {
            int id = 0;
            for (CsvReader.Row row : dataRows(LEAGUES_FILE)) {
                leagueIds.put(row.get(0).trim(), ++id);
                ps.setInt(1, id);
                ps.setString(2, row.get(0).trim());
                ps.setString(3, row.get(1).trim());
                ps.setInt(4, Integer.parseInt(row.get(2).trim()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Reads a CSV file and drops its header row. */
    private List<CsvReader.Row> dataRows(String file) throws IOException {
        List<CsvReader.Row> rows = CsvReader.read(dataDir.resolve(file));
        return rows.isEmpty() ? rows : rows.subList(1, rows.size());
    }

    // -------------------------------------------------------------------- staging

    /** Keeps an untouched copy of the input so every loaded value can be traced back. */
    private void stageRawRows(List<CsvReader.Row> rows) throws SQLException {
        String sql = "INSERT INTO stg_match_raw VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (CsvReader.Row row : rows) {
                ps.setInt(1, row.lineNo());
                for (int i = 0; i < MatchTransformer.HEADER.size(); i++) {
                    ps.setString(i + 2, row.get(i));
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ----------------------------------------------------------------------- load

    private void loadSeasons(List<MatchRecord> matches) throws SQLException {
        Map<Integer, String> seasons = new TreeMap<>();      // ordered by start year
        matches.forEach(m -> seasons.put(m.seasonStartYear(), m.season()));
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO season (season_id, season_label, start_year) VALUES (?, ?, ?)")) {
            int id = 0;
            for (Map.Entry<Integer, String> season : seasons.entrySet()) {
                seasonIds.put(season.getValue(), ++id);
                ps.setInt(1, id);
                ps.setString(2, season.getValue());
                ps.setInt(3, season.getKey());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** @return number of set rows inserted */
    private int loadMatches(List<MatchRecord> matches) throws SQLException {
        // Chronological order gives stable, meaningful surrogate keys.
        List<MatchRecord> ordered = new ArrayList<>(matches);
        ordered.sort(Comparator.comparing(MatchRecord::date)
                .thenComparing(MatchRecord::leagueCode)
                .thenComparingInt(MatchRecord::matchNo));

        String matchSql = "INSERT INTO match (match_id, match_uuid, season_id, league_id, match_no, match_date, match_time,"
                + " home_team_id, away_team_id, venue_id, status, home_sets, away_sets, duration_min, is_forfeit)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String setSql = "INSERT INTO set_score (match_id, set_no, home_points, away_points) VALUES (?, ?, ?, ?)";
        int setCount = 0;
        try (PreparedStatement matchPs = db.prepareStatement(matchSql);
             PreparedStatement setPs = db.prepareStatement(setSql)) {
            int matchId = 0;
            for (MatchRecord m : ordered) {
                matchId++;
                matchPs.setInt(1, matchId);
                matchPs.setString(2, m.matchUuid());
                matchPs.setInt(3, seasonIds.get(m.season()));
                matchPs.setInt(4, leagueIds.get(m.leagueCode()));
                matchPs.setInt(5, m.matchNo());
                matchPs.setString(6, m.date().toString());                       // ISO-8601, sorts correctly as text
                matchPs.setString(7, m.time() == null ? null : m.time().format(DateTimeFormatter.ofPattern("HH:mm")));
                matchPs.setInt(8, teamId(m.home()));
                matchPs.setInt(9, teamId(m.away()));
                matchPs.setObject(10, m.venue() == null ? null : venueId(m.venue()), Types.INTEGER);
                matchPs.setString(11, m.played() ? "played" : "scheduled");
                matchPs.setObject(12, m.homeSets(), Types.INTEGER);
                matchPs.setObject(13, m.awaySets(), Types.INTEGER);
                matchPs.setObject(14, m.durationMin(), Types.INTEGER);
                matchPs.setInt(15, m.forfeit() ? 1 : 0);
                matchPs.addBatch();
                for (SetScore set : m.sets()) {
                    setPs.setInt(1, matchId);
                    setPs.setInt(2, set.setNo());
                    setPs.setInt(3, set.homePoints());
                    setPs.setInt(4, set.awayPoints());
                    setPs.addBatch();
                    setCount++;
                }
            }
            matchPs.executeBatch();
            setPs.executeBatch();
        }
        return setCount;
    }

    /** Get-or-create for the team dimension. */
    private int teamId(TeamNameNormalizer.TeamRef team) throws SQLException {
        Integer id = teamIds.get(team.teamName());
        if (id == null) {
            id = teamIds.size() + 1;
            teamIds.put(team.teamName(), id);
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO team (team_id, club_id, squad_no, team_name) VALUES (?, ?, ?, ?)")) {
                ps.setInt(1, id);
                ps.setInt(2, clubIds.get(team.clubCode()));
                ps.setInt(3, team.squadNo());
                ps.setString(4, team.teamName());
                ps.executeUpdate();
            }
        }
        return id;
    }

    /** Get-or-create for the venue dimension. */
    private int venueId(String venue) throws SQLException {
        Integer id = venueIds.get(venue);
        if (id == null) {
            id = venueIds.size() + 1;
            venueIds.put(venue, id);
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO venue (venue_id, venue_name) VALUES (?, ?)")) {
                ps.setInt(1, id);
                ps.setString(2, venue);
                ps.executeUpdate();
            }
        }
        return id;
    }

    /**
     * Loads the standings published by the federation. They are not used for any
     * metric; they exist so {@code v_standings_reconciliation} can prove that the
     * standings computed from match data agree with the official ones.
     */
    private void loadOfficialStandings(TeamNameNormalizer normalizer) throws IOException, SQLException {
        Path file = dataDir.resolve(STANDINGS_FILE);
        if (!Files.exists(file)) {
            return;                                         // optional input
        }
        Set<String> competitionsWithMatches = new HashSet<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT season_id, league_id FROM match")) {
            while (rs.next()) {
                competitionsWithMatches.add(rs.getInt(1) + "|" + rs.getInt(2));
            }
        }
        String sql = "INSERT OR IGNORE INTO official_standing (season_id, league_id, team_id, rank, played, won, lost,"
                + " points, sets_won, sets_lost, balls_won, balls_lost) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (CsvReader.Row row : dataRows(STANDINGS_FILE)) {
                Integer seasonId = seasonIds.get(row.get(0).trim());
                Integer leagueId = leagueIds.get(row.get(1).trim());
                var team = normalizer.normalize(row.get(3));
                if (seasonId == null || leagueId == null || !competitionsWithMatches.contains(seasonId + "|" + leagueId)) {
                    // e.g. a division that did not exist that season: the site repeats another table
                    log(STANDINGS_FILE, row, "WARNING", "STANDINGS_WITHOUT_MATCHES",
                            "No matches loaded for " + row.get(0) + " " + row.get(1));
                    continue;
                }
                if (team.isEmpty() || !teamIds.containsKey(team.get().teamName())) {
                    log(STANDINGS_FILE, row, "WARNING", "UNKNOWN_TEAM", "'" + row.get(3) + "' has no loaded matches");
                    continue;
                }
                try {
                    ps.setInt(1, seasonId);
                    ps.setInt(2, leagueId);
                    ps.setInt(3, teamIds.get(team.get().teamName()));
                    // source columns: rank(2) played(4) won(5) lost(6) points(13) sets(14,15) balls(17,18)
                    int[] columns = {2, 4, 5, 6, 13, 14, 15, 17, 18};
                    for (int i = 0; i < columns.length; i++) {
                        ps.setInt(4 + i, leadingInt(row.get(columns[i])));
                    }
                    ps.addBatch();
                } catch (NumberFormatException e) {
                    log(STANDINGS_FILE, row, "WARNING", "INVALID_NUMBER", e.getMessage());
                }
            }
            ps.executeBatch();
        }
    }

    /**
     * Parses the number at the start of a cell. The current season's rank column
     * carries a movement marker after the number ("2 \u25BC1" = down one place).
     */
    static int leadingInt(String cell) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d+)").matcher(cell);
        if (!m.find()) {
            throw new NumberFormatException("No leading number in '" + cell + "'");
        }
        return Integer.parseInt(m.group(1));
    }

    // ------------------------------------------------------------------- metadata

    private void log(String file, CsvReader.Row row, String severity, String code, String detail) {
        if (severity.equals("REJECT")) {
            rejected++;
        } else {
            warnings++;
        }
        String sql = "INSERT INTO etl_reject (source_file, line_no, severity, reason_code, detail, raw_record) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, file);
            ps.setInt(2, row.lineNo());
            ps.setString(3, severity);
            ps.setString(4, code);
            ps.setString(5, detail);
            ps.setString(6, row.raw());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot write to etl_reject", e);
        }
    }

    private void recordRun(String startedAt, Result result) throws SQLException {
        String sql = "INSERT INTO etl_run (started_at, finished_at, source_file, rows_read, matches_loaded, sets_loaded,"
                + " rows_rejected, warnings) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, startedAt);
            ps.setString(2, now());
            ps.setString(3, MATCH_FILE);
            ps.setInt(4, result.rowsRead());
            ps.setInt(5, result.matchesLoaded());
            ps.setInt(6, result.setsLoaded());
            ps.setInt(7, result.rejected());
            ps.setInt(8, result.warnings());
            ps.executeUpdate();
        }
    }

    private static String now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
