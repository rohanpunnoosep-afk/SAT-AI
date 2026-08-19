package satapp.db;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {

    public static final String DEFAULT_DB_PATH = "data/questions.db";

    private static final String SCHEMA_QUESTIONS =
        "CREATE TABLE IF NOT EXISTS questions (\n" +
        "  id TEXT PRIMARY KEY,\n" +
        "  external_id TEXT,\n" +
        "  section TEXT NOT NULL,\n" +
        "  domain TEXT NOT NULL,\n" +
        "  skill TEXT NOT NULL,\n" +
        "  difficulty TEXT NOT NULL,\n" +
        "  question_type TEXT NOT NULL,\n" +
        "  stimulus TEXT,\n" +
        "  stem TEXT NOT NULL,\n" +
        "  choices_json TEXT,\n" +
        "  correct_answer TEXT NOT NULL,\n" +
        "  explanation TEXT,\n" +
        "  source TEXT NOT NULL DEFAULT 'official',\n" +
        "  parent_question_id TEXT,\n" +
        "  FOREIGN KEY (parent_question_id) REFERENCES questions(id)\n" +
        ")";

    private static final String SCHEMA_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_q_filters ON questions(section, domain, skill, difficulty)";

    public static Connection open(String dbPath) throws SQLException {
        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    public static void initSchema(Connection c) throws SQLException {
        try (Statement stmt = c.createStatement()) {
            stmt.execute(SCHEMA_QUESTIONS);
            stmt.execute(SCHEMA_INDEX);
        }
    }

    public static String resolveDbPath() {
        String envPath = System.getenv("DB_PATH");
        if (envPath != null && !envPath.trim().isEmpty()) {
            return envPath;
        }
        return DEFAULT_DB_PATH;
    }
}
