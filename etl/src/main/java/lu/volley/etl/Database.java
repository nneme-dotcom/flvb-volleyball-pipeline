package lu.volley.etl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Small helpers around the JDBC connection. */
public final class Database {

    private Database() {
    }

    /** Opens (and creates if needed) the SQLite file with foreign keys enforced. */
    public static Connection open(Path dbFile) throws SQLException, IOException {
        Path parent = dbFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");   // off by default in SQLite
        }
        return connection;
    }

    /** Executes every statement of a .sql file. */
    public static void runScript(Connection connection, Path script) throws SQLException, IOException {
        String sql = Files.readString(script, StandardCharsets.UTF_8);
        try (Statement st = connection.createStatement()) {
            for (String statement : splitStatements(sql)) {
                try {
                    st.execute(statement);
                } catch (SQLException e) {
                    throw new SQLException(script.getFileName() + ": " + e.getMessage() + "\n" + statement, e);
                }
            }
        }
    }

    /**
     * Splits a script into statements: line comments are removed, then the text
     * is cut at semicolons. Sufficient for DDL and views (the scripts contain no
     * triggers and no semicolons or "--" inside string literals).
     */
    static List<String> splitStatements(String sql) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\\R")) {
            int comment = line.indexOf("--");
            withoutComments.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : withoutComments.toString().split(";")) {
            if (!part.isBlank()) {
                statements.add(part.strip());
            }
        }
        return statements;
    }
}
