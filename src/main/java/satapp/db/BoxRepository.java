package satapp.db;

import satapp.model.Box;
import satapp.model.Question;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class BoxRepository {

    private final Connection conn;

    public BoxRepository(Connection conn) {
        this.conn = conn;
    }

    public Box create(String id, String label) throws SQLException {
        long createdAt = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO boxes (id, label, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, label);
            ps.setLong(3, createdAt);
            ps.executeUpdate();
        }
        return new Box(id, label, createdAt);
    }

    public Box findById(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM boxes WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapBox(rs);
                }
                return null;
            }
        }
    }

    public Box findByLabel(String label) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM boxes WHERE label = ?")) {
            ps.setString(1, label);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapBox(rs);
                }
                return null;
            }
        }
    }

    public List<Box> listAll() throws SQLException {
        List<Box> boxes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM boxes ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                boxes.add(mapBox(rs));
            }
        }
        return boxes;
    }

    public int countQuestions(String boxId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM box_questions WHERE box_id = ?")) {
            ps.setString(1, boxId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public void addQuestion(String boxId, String questionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO box_questions (box_id, question_id, added_at) VALUES (?, ?, ?)")) {
            ps.setString(1, boxId);
            ps.setString(2, questionId);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public void removeQuestion(String boxId, String questionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM box_questions WHERE box_id = ? AND question_id = ?")) {
            ps.setString(1, boxId);
            ps.setString(2, questionId);
            ps.executeUpdate();
        }
    }

    public List<Question> listQuestions(String boxId) throws SQLException {
        String sql = "SELECT q.* FROM questions q " +
            "JOIN box_questions bq ON bq.question_id = q.id " +
            "WHERE bq.box_id = ? ORDER BY bq.added_at";
        List<Question> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, boxId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapQuestion(rs));
                }
            }
        }
        return results;
    }

    private static Box mapBox(ResultSet rs) throws SQLException {
        return new Box(rs.getString("id"), rs.getString("label"), rs.getLong("created_at"));
    }

    private static Question mapQuestion(ResultSet rs) throws SQLException {
        return new Question(
            rs.getString("id"),
            rs.getString("external_id"),
            rs.getString("cb_question_id"),
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
