package lu.volley.etl;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Publishes the reporting layer as CSV files arranged as a star schema
 * (dimensions + facts) so Power BI can load them without any transformation.
 */
public final class Exporter {

    /** Output file name -> query. Facts carry the keys of the dimensions they join to. */
    private static final Map<String, String> EXPORTS = new LinkedHashMap<>();

    static {
        EXPORTS.put("dim_team.csv",
                "SELECT t.team_id, t.team_name, c.club_code, c.club_name, t.squad_no"
                        + " FROM team t JOIN club c ON c.club_id = t.club_id ORDER BY t.team_name");
        EXPORTS.put("dim_season.csv",
                "SELECT season_label AS season, start_year FROM season ORDER BY start_year");
        EXPORTS.put("dim_league.csv",
                "SELECT league_code, league_name, tier FROM league ORDER BY tier");
        EXPORTS.put("fact_match.csv",
                "SELECT match_id, season, league_code, match_no, match_date, match_time, status, home_team_id, home_team,"
                        + " away_team_id, away_team, home_sets, away_sets, score, winner, home_win, home_points, away_points,"
                        + " duration_min, is_forfeit, venue FROM v_match ORDER BY match_id");
        EXPORTS.put("fact_team_match.csv",
                "SELECT match_id, season, league_code, match_date, team_id, team, opponent_id, opponent, venue_type, won,"
                        + " score, sets_for, sets_against, points_for, points_against, league_points, duration_min, is_forfeit"
                        + " FROM v_team_match ORDER BY match_id, venue_type DESC");
        EXPORTS.put("fact_set.csv",
                "SELECT match_id, season, league_code, match_date, set_no, home_team, away_team, home_points, away_points,"
                        + " total_points, margin, home_won_set, went_to_extra_points, is_deciding_set"
                        + " FROM v_set ORDER BY match_id, set_no");
        EXPORTS.put("standings.csv",
                "SELECT season, league_code, league_name, rank, team_id, team, played, won, lost, w_3_0, w_3_1, w_3_2,"
                        + " l_2_3, l_1_3, l_0_3, points, sets_won, sets_lost, set_ratio, balls_won, balls_lost, ball_ratio,"
                        + " win_pct, points_per_match FROM v_standings ORDER BY season, league_code, rank");
    }

    private Exporter() {
    }

    /** @return the files written */
    public static List<Path> exportAll(Connection db, Path outputDir) throws SQLException, IOException {
        List<Path> written = new ArrayList<>();
        for (Map.Entry<String, String> export : EXPORTS.entrySet()) {
            Path file = outputDir.resolve(export.getKey());
            exportQuery(db, export.getValue(), file);
            written.add(file);
        }
        written.add(exportDateDimension(db, outputDir.resolve("dim_date.csv")));
        return written;
    }

    static void exportQuery(Connection db, String sql, Path file) throws SQLException, IOException {
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery(sql);
             CsvWriter out = new CsvWriter(file)) {
            ResultSetMetaData meta = rs.getMetaData();
            int columns = meta.getColumnCount();
            List<String> row = new ArrayList<>(columns);
            for (int i = 1; i <= columns; i++) {
                row.add(meta.getColumnLabel(i));
            }
            out.writeRow(row);
            while (rs.next()) {
                row.clear();
                for (int i = 1; i <= columns; i++) {
                    row.add(rs.getString(i));              // NULL becomes an empty field
                }
                out.writeRow(row);
            }
        }
    }

    /** A continuous calendar covering every match date, as Power BI time intelligence requires. */
    private static Path exportDateDimension(Connection db, Path file) throws SQLException, IOException {
        LocalDate first;
        LocalDate last;
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT MIN(match_date), MAX(match_date) FROM match")) {
            rs.next();
            if (rs.getString(1) == null) {
                return file;
            }
            first = LocalDate.parse(rs.getString(1)).withDayOfMonth(1);
            last = LocalDate.parse(rs.getString(2));
            last = last.withDayOfMonth(last.lengthOfMonth());
        }
        try (CsvWriter out = new CsvWriter(file)) {
            out.writeRow(List.of("date", "year", "month_no", "month_name", "year_month", "iso_week",
                    "weekday_no", "weekday_name", "is_weekend", "season"));
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                int seasonStart = d.getMonthValue() >= 7 ? d.getYear() : d.getYear() - 1;   // seasons start in summer
                out.writeRow(List.of(
                        d.toString(),
                        String.valueOf(d.getYear()),
                        String.valueOf(d.getMonthValue()),
                        d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                        String.format("%d-%02d", d.getYear(), d.getMonthValue()),
                        String.valueOf(d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)),
                        String.valueOf(d.getDayOfWeek().getValue()),
                        d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                        d.getDayOfWeek().getValue() >= 6 ? "1" : "0",
                        seasonStart + "/" + (seasonStart + 1)));
            }
        }
        return file;
    }
}
