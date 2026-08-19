package satapp.db;

import satapp.model.Question;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class QuestionRepository {

    private static final List<String> ALLOWED_DISTINCT_COLUMNS =
        Arrays.asList("section", "domain", "skill", "difficulty");

    private final Connection conn;

    public QuestionRepository(Connection conn) {
        this.conn = conn;
    }

    public List<Question> find(String section, String domain, String skill, String difficulty,
                                String search, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM questions WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (section != null && !section.trim().isEmpty()) {
            sql.append(" AND section = ?");
            params.add(section);
        }
        if (domain != null && !domain.trim().isEmpty()) {
            sql.append(" AND domain = ?");
            params.add(domain);
        }
        if (skill != null && !skill.trim().isEmpty()) {
            sql.append(" AND skill = ?");
            params.add(skill);
        }
        if (difficulty != null && !difficulty.trim().isEmpty()) {
            sql.append(" AND difficulty = ?");
            params.add(difficulty);
        }
        if (search != null && !search.trim().isEmpty()) {
            sql.append(" AND stem LIKE ? ESCAPE '\\'");
            params.add("%" + escapeLike(search) + "%");
        }
        sql.append(" LIMIT ?");
        params.add(limit);

        List<Question> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        }
        return results;
    }

    public Question findById(String id) throws SQLException {
        String sql = "SELECT * FROM questions WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
                return null;
            }
        }
    }

    public List<String> distinctValues(String column) throws SQLException {
        if (!ALLOWED_DISTINCT_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("Unsupported column: " + column);
        }
        String sql = "SELECT DISTINCT " + column + " FROM questions ORDER BY " + column;
        List<String> values = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Question mapRow(ResultSet rs) throws SQLException {
        return new Question(
            rs.getString("id"),
            rs.getString("external_id"),
            rs.getString("section"),
            rs.getString("domain"),
            rs.getString("skill"),
            rs.getString("difficulty"),
            rs.getString("question_type"),
            rs.getString("stimulus"),
            rs.getString("stem"),
            rs.getString("choices_json"),
            rs.getString("correct_answer"),
            rs.getString("explanation"),
            rs.getString("source"),
            rs.getString("parent_question_id")
        );
    }
}
