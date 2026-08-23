package satapp.tools;

import org.json.JSONArray;
import org.json.JSONObject;
import satapp.db.Database;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public class ImportQuestions {

    private static final String INSERT_SQL =
        "INSERT OR REPLACE INTO questions " +
        "(id, external_id, cb_question_id, section, domain, skill, difficulty, question_type, stimulus, stem, " +
        "choices_json, correct_answer, explanation, source, parent_question_id) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ImportQuestions <input.json> [dbPath]");
            System.exit(2);
            return;
        }

        String inputPath = args[0];
        String dbPath = args.length >= 2 ? args[1] : Database.resolveDbPath();

        String text = new String(Files.readAllBytes(Paths.get(inputPath)), StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(text);

        int skipped = 0;
        int imported = 0;

        try (Connection conn = Database.open(dbPath)) {
            Database.initSchema(conn);
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                for (String uId : root.keySet()) {
                    JSONObject entry = root.getJSONObject(uId);
                    JSONObject content = entry.optJSONObject("content");

                    // Older bank entries use a different shape (prompt / answer.choices
                    // instead of stem / answerOptions); fold them into the standard shape.
                    if (content != null && !content.has("stem") && content.has("prompt")) {
                        content = normalizeLegacy(content);
                        if (content == null) {
                            System.err.println("SKIP " + uId + ": legacy entry has no gradeable answer");
                            skipped++;
                            continue;
                        }
                    }

                    String stem = content != null ? content.optString("stem", null) : null;
                    if (stem == null) {
                        System.err.println("SKIP " + uId + ": missing content.stem");
                        skipped++;
                        continue;
                    }

                    String type = content.optString("type", null);
                    String correctAnswer = resolveCorrectAnswer(content, type);
                    if (correctAnswer == null) {
                        System.err.println("SKIP " + uId + ": no resolvable correct_answer");
                        skipped++;
                        continue;
                    }

                    // The College Board question ID -- the short id shown in the
                    // official Question Bank UI, which students quote when asking
                    // about a specific question.
                    String cbQuestionId = trimToNull(entry.optString("questionId", null));

                    String module = entry.optString("module", null);
                    String section = "math".equals(module) ? "Math"
                        : "english".equals(module) ? "Reading and Writing"
                        : module;

                    String difficultyCode = entry.optString("difficulty", null);
                    String difficulty = "E".equals(difficultyCode) ? "Easy"
                        : "M".equals(difficultyCode) ? "Medium"
                        : "H".equals(difficultyCode) ? "Hard"
                        : difficultyCode;

                    String domain = entry.optString("primary_class_cd_desc", null);
                    String skill = entry.optString("skill_desc", null);
                    String stimulus = content.has("stimulus") && !content.isNull("stimulus")
                        ? content.optString("stimulus") : null;
                    String explanation = content.optString("rationale", null);

                    String choicesJson = buildChoicesJson(content, type);

                    ps.setString(1, uId);
                    ps.setString(2, uId);
                    setNullableString(ps, 3, cbQuestionId);
                    ps.setString(4, section);
                    ps.setString(5, domain);
                    ps.setString(6, skill);
                    ps.setString(7, difficulty);
                    ps.setString(8, type);
                    setNullableString(ps, 9, stimulus);
                    ps.setString(10, stem);
                    setNullableString(ps, 11, choicesJson);
                    ps.setString(12, correctAnswer);
                    setNullableString(ps, 13, explanation);
                    ps.setString(14, "official");
                    ps.setNull(15, Types.VARCHAR);

                    ps.addBatch();
                    imported++;
                }
                ps.executeBatch();
            }

            conn.commit();

            printBreakdown(conn);
        }

        if (skipped > 0) {
            System.err.println("Skipped " + skipped + " entries");
        }
    }

    private static String resolveCorrectAnswer(JSONObject content, String type) {
        if ("spr".equals(type)) {
            JSONArray keys = content.optJSONArray("keys");
            if (keys == null || keys.length() == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < keys.length(); i++) {
                if (i > 0) sb.append(",");
                sb.append(keys.getString(i).trim());
            }
            return sb.toString();
        } else {
            return resolveMcqChoiceId(content);
        }
    }

    /**
     * The UI submits the answerOption id, so the stored answer must be that id.
     * The bank identifies the correct choice three different ways: content.keys
     * holds the option id, content.correct_answer sometimes holds that same id,
     * and sometimes holds a letter that indexes into answerOptions.
     */
    private static String resolveMcqChoiceId(JSONObject content) {
        JSONArray options = content.optJSONArray("answerOptions");
        if (options == null || options.length() == 0) {
            return null;
        }

        JSONArray keys = content.optJSONArray("keys");
        if (keys != null && keys.length() > 0) {
            String candidate = keys.optString(0, "").trim();
            if (indexOfOptionId(options, candidate) >= 0) {
                return candidate;
            }
        }

        JSONArray correct = content.optJSONArray("correct_answer");
        if (correct == null || correct.length() == 0) {
            return null;
        }
        String value = correct.optString(0, "").trim();
        if (value.isEmpty()) {
            return null;
        }
        if (indexOfOptionId(options, value) >= 0) {
            return value;
        }
        if (value.length() == 1) {
            int index = Character.toUpperCase(value.charAt(0)) - 'A';
            if (index >= 0 && index < options.length()) {
                return options.getJSONObject(index).optString("id", null);
            }
        }
        return null;
    }

    private static int indexOfOptionId(JSONArray options, String id) {
        if (id == null || id.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < options.length(); i++) {
            if (id.equals(options.getJSONObject(i).optString("id", null))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Converts a legacy entry (prompt / body / answer.choices) into the standard
     * content shape. Returns null when the entry carries no usable answer -- legacy
     * grid-in entries ship a rationale only, so they cannot be graded.
     */
    private static JSONObject normalizeLegacy(JSONObject content) {
        JSONObject answer = content.optJSONObject("answer");
        if (answer == null) {
            return null;
        }
        JSONObject choices = answer.optJSONObject("choices");
        String correctChoice = answer.optString("correct_choice", null);
        if (choices == null || correctChoice == null || correctChoice.trim().isEmpty()) {
            return null;
        }

        JSONArray options = new JSONArray();
        for (String key : new TreeSet<>(choices.keySet())) {
            JSONObject option = new JSONObject();
            option.put("id", key);
            option.put("content", choices.getJSONObject(key).optString("body", ""));
            options.put(option);
        }

        JSONObject normalized = new JSONObject();
        normalized.put("stem", content.optString("prompt", ""));
        if (content.has("body") && !content.isNull("body")) {
            normalized.put("stimulus", content.optString("body"));
        }
        normalized.put("type", "mcq");
        normalized.put("answerOptions", options);
        normalized.put("correct_answer", new JSONArray().put(correctChoice.trim()));
        if (answer.has("rationale") && !answer.isNull("rationale")) {
            normalized.put("rationale", answer.optString("rationale"));
        }
        return normalized;
    }

    private static String buildChoicesJson(JSONObject content, String type) {
        if ("spr".equals(type)) {
            return null;
        }
        JSONArray options = content.optJSONArray("answerOptions");
        if (options == null) {
            return null;
        }
        JSONArray reshaped = new JSONArray();
        for (int i = 0; i < options.length(); i++) {
            JSONObject opt = options.getJSONObject(i);
            JSONObject choice = new JSONObject();
            choice.put("id", opt.optString("id", null));
            choice.put("text", opt.optString("content", null));
            reshaped.put(choice);
        }
        return reshaped.toString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void printBreakdown(Connection conn) throws SQLException {
        try (PreparedStatement countPs = conn.prepareStatement("SELECT COUNT(*) FROM questions");
             ResultSet rs = countPs.executeQuery()) {
            rs.next();
            System.out.println("IMPORT_OK rows=" + rs.getInt(1));
        }

        System.out.println("By section:");
        printGroupBy(conn, "section");

        System.out.println("By difficulty:");
        printGroupBy(conn, "difficulty");
    }

    private static void printGroupBy(Connection conn, String column) throws SQLException {
        String sql = "SELECT " + column + ", COUNT(*) FROM questions GROUP BY " + column + " ORDER BY " + column;
        Map<String, Integer> counts = new TreeMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getInt(2));
            }
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue());
        }
    }
}
