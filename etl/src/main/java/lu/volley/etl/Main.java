package lu.volley.etl;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.List;

/**
 * Command-line entry point.
 *
 * <pre>
 *   java lu.volley.etl.Main [--data DIR] [--sql DIR] [--db FILE] [--export DIR]
 * </pre>
 * Defaults assume the command is run from the {@code etl/} directory of the
 * repository, so {@code mvn exec:java} works with no arguments.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Path data = Path.of("../data");
        Path sql = Path.of("../sql");
        Path dbFile = Path.of("../build/volleyball.db");
        Path export = Path.of("../powerbi/data");

        for (int i = 0; i < args.length; i++) {
            if (i + 1 >= args.length && args[i].startsWith("--") && !args[i].equals("--help")) {
                usage("Missing value for " + args[i]);
            }
            switch (args[i]) {
                case "--data" -> data = Path.of(args[++i]);
                case "--sql" -> sql = Path.of(args[++i]);
                case "--db" -> dbFile = Path.of(args[++i]);
                case "--export" -> export = Path.of(args[++i]);
                case "--help", "-h" -> usage(null);
                default -> usage("Unknown option " + args[i]);
            }
        }

        long start = System.currentTimeMillis();
        try (Connection db = Database.open(dbFile)) {
            System.out.println("[1/3] Loading " + data.resolve(Pipeline.MATCH_FILE) + " -> " + dbFile);
            Pipeline.Result result = new Pipeline(data, sql, db).run();
            System.out.printf("      rows read: %d | matches loaded: %d | sets loaded: %d | rejected: %d | warnings: %d%n",
                    result.rowsRead(), result.matchesLoaded(), result.setsLoaded(), result.rejected(), result.warnings());

            System.out.println("[2/3] Data-quality report");
            printQuery(db, "SELECT severity, reason_code, COUNT(*) AS rows FROM etl_reject GROUP BY 1, 2 ORDER BY 1, 3 DESC");
            printQuery(db, "SELECT standings_rows_checked, standings_rows_reconciled, matches_with_set_count_mismatch FROM v_dq_summary");

            System.out.println("[3/3] Exporting Power BI dataset -> " + export);
            List<Path> files = Exporter.exportAll(db, export);
            files.forEach(f -> System.out.println("      " + f.getFileName()));
        }
        System.out.printf("Done in %.1f s%n", (System.currentTimeMillis() - start) / 1000.0);
    }

    /** Prints a small result set as an aligned table. */
    private static void printQuery(Connection db, String sql) throws Exception {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            StringBuilder line = new StringBuilder("      ");
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                line.append(String.format("%-32s", meta.getColumnLabel(i)));
            }
            System.out.println(line.toString().stripTrailing());
            while (rs.next()) {
                line = new StringBuilder("      ");
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    line.append(String.format("%-32s", rs.getString(i)));
                }
                System.out.println(line.toString().stripTrailing());
            }
        }
    }

    private static void usage(String error) {
        if (error != null) {
            System.err.println(error);
        }
        System.err.println("Usage: java lu.volley.etl.Main [--data DIR] [--sql DIR] [--db FILE] [--export DIR]");
        System.exit(error == null ? 0 : 2);
    }
}
