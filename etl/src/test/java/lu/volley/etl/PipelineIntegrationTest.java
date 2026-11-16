package lu.volley.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the whole pipeline on the sample data shipped in the repository and
 * checks invariants that must hold whatever the data looks like.
 */
class PipelineIntegrationTest {

    private static final Path REPO = Path.of("..");

    @TempDir
    static Path tmp;
    static Connection db;
    static Pipeline.Result result;

    @BeforeAll
    static void runPipeline() throws Exception {
        db = Database.open(tmp.resolve("test.db"));
        result = new Pipeline(REPO.resolve("data"), REPO.resolve("sql"), db).run();
    }

    /** Windows cannot delete the temp directory while the database file is open. */
    @AfterAll
    static void closeDatabase() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    private static long scalar(String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void everySourceRowIsEitherLoadedOrRejected() throws SQLException {
        assertTrue(result.matchesLoaded() > 0);
        assertEquals(result.rowsRead(), result.matchesLoaded() + result.rejected());
        assertEquals(result.rowsRead(), scalar("SELECT COUNT(*) FROM stg_match_raw"));
        assertEquals(result.rejected(), scalar("SELECT COUNT(*) FROM etl_reject WHERE severity = 'REJECT'"));
    }

    @Test
    void setScoresAgreeWithMatchResults() throws SQLException {
        assertEquals(0, scalar("SELECT matches_with_set_count_mismatch FROM v_dq_summary"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM match m WHERE status = 'played' AND home_sets <>"
                + " (SELECT COUNT(*) FROM set_score s WHERE s.match_id = m.match_id AND s.home_points > s.away_points)"));
    }

    @Test
    void teamPerspectiveViewHasTwoRowsPerPlayedMatch() throws SQLException {
        assertEquals(2 * scalar("SELECT COUNT(*) FROM match WHERE status = 'played'"),
                scalar("SELECT COUNT(*) FROM v_team_match"));
        // every match has exactly one winner, and points are zero-sum across the league
        assertEquals(scalar("SELECT COUNT(*) FROM match WHERE status = 'played'"), scalar("SELECT SUM(won) FROM v_team_match"));
        assertEquals(scalar("SELECT SUM(balls_won) FROM v_standings"), scalar("SELECT SUM(balls_lost) FROM v_standings"));
    }

    @Test
    void noForeignKeyViolations() throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
            assertTrue(!rs.next(), "foreign_key_check returned rows");
        }
    }

    @Test
    void computedStandingsReproduceThePublishedOnes() throws SQLException {
        long checked = scalar("SELECT COUNT(*) FROM v_standings_reconciliation");
        long identical = scalar("SELECT COUNT(*) FROM v_standings_reconciliation WHERE results_match = 1");
        assertTrue(checked > 100, "official standings were loaded");
        // The few known differences are documented in docs/data_quality.md.
        assertTrue(identical >= checked * 0.97, identical + " of " + checked + " standings rows reconcile");
    }

    @Test
    void rerunningIsIdempotent() throws Exception {
        long before = scalar("SELECT COUNT(*) FROM match");
        Pipeline.Result second = new Pipeline(REPO.resolve("data"), REPO.resolve("sql"), db).run();
        assertEquals(result, second);
        assertEquals(before, scalar("SELECT COUNT(*) FROM match"));
    }

    @Test
    void exportsStarSchemaFiles() throws Exception {
        List<Path> files = Exporter.exportAll(db, tmp.resolve("export"));
        assertEquals(8, files.size());
        for (Path file : files) {
            assertTrue(Files.size(file) > 0, file + " is empty");
        }
        long exportedRows;
        try (var lines = Files.lines(tmp.resolve("export/fact_match.csv"))) {
            exportedRows = lines.count() - 1;
        }
        assertEquals(scalar("SELECT COUNT(*) FROM match"), exportedRows);
    }
}
