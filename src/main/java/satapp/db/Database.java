package satapp.db;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {

    public static final String DEFAULT_DB_PATH = "data/questions.db";

    private static final String SCHEMA_QUESTIONS =
        "CREATE TABLE IF NOT EXISTS questions (\n" +
        "  id TEXT PRIMARY KEY,\n" +
        "  external_id TEXT,\n" +
        "  cb_question_id TEXT,\n" +
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

    private static final String SCHEMA_CB_ID_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_q_cb_question_id ON questions(cb_question_id)";

    private static final String SCHEMA_BOXES =
        "CREATE TABLE IF NOT EXISTS boxes (\n" +
        "  id TEXT PRIMARY KEY,\n" +
        "  label TEXT NOT NULL,\n" +
        "  created_at INTEGER NOT NULL\n" +
        ")";

    private static final String SCHEMA_BOX_QUESTIONS =
        "CREATE TABLE IF NOT EXISTS box_questions (\n" +
        "  box_id TEXT NOT NULL,\n" +
        "  question_id TEXT NOT NULL,\n" +
        "  added_at INTEGER NOT NULL,\n" +
        "  PRIMARY KEY (box_id, question_id),\n" +
        "  FOREIGN KEY (box_id) REFERENCES boxes(id),\n" +
        "  FOREIGN KEY (question_id) REFERENCES questions(id)\n" +
        ")";

    private static final String SCHEMA_BOX_QUESTIONS_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_box_questions_box ON box_questions(box_id)";

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
            addColumnIfMissing(c, stmt, "cb_question_id", "TEXT");
            stmt.execute(SCHEMA_INDEX);
            stmt.execute(SCHEMA_CB_ID_INDEX);
            stmt.execute(SCHEMA_BOXES);
            stmt.execute(SCHEMA_BOX_QUESTIONS);
            stmt.execute(SCHEMA_BOX_QUESTIONS_INDEX);
        }
    }

    /** Brings databases created before a column existed up to the current schema. */
    private static void addColumnIfMissing(Connection c, Statement stmt, String column, String type)
            throws SQLException {
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(questions)")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement alter = c.createStatement()) {
            alter.execute("ALTER TABLE questions ADD COLUMN " + column + " " + type);
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
