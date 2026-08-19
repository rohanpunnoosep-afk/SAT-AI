package satapp.web;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.json.JSONArray;
import org.json.JSONObject;
import satapp.db.Database;
import satapp.db.QuestionRepository;
import satapp.model.Question;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class WebServer {

    public static int resolvePort() {
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.trim().isEmpty()) {
            try {
                return Integer.parseInt(portEnv.trim());
            } catch (NumberFormatException e) {
                // fall through to default
            }
        }
        return 8080;
    }

    public static Javalin start(String dbPath, int port) throws SQLException {
        Connection conn = Database.open(dbPath);
        Database.initSchema(conn);
        QuestionRepository repo = new QuestionRepository(conn);

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
        });

        app.get("/api/questions", ctx -> {
            String section = ctx.queryParam("section");
            String domain = ctx.queryParam("domain");
            String skill = ctx.queryParam("skill");
            String difficulty = ctx.queryParam("difficulty");
            String search = ctx.queryParam("search");

            int limit = 100;
            String limitParam = ctx.queryParam("limit");
            if (limitParam != null && !limitParam.trim().isEmpty()) {
                try {
                    limit = Integer.parseInt(limitParam.trim());
                } catch (NumberFormatException ignored) {
                    // keep default
                }
            }
            if (limit > 500) {
                limit = 500;
            }
            if (limit < 1) {
                limit = 1;
            }

            List<Question> questions = repo.find(section, domain, skill, difficulty, search, limit);
            JSONArray arr = new JSONArray();
            for (Question q : questions) {
                JSONObject obj = new JSONObject();
                obj.put("id", q.getId());
                obj.put("section", q.getSection());
                obj.put("domain", q.getDomain());
                obj.put("skill", q.getSkill());
                obj.put("difficulty", q.getDifficulty());
                obj.put("question_type", q.getQuestionType());
                obj.put("source", q.getSource());
                arr.put(obj);
            }
            ctx.contentType("application/json");
            ctx.result(arr.toString());
        });

        app.get("/api/questions/{id}", ctx -> {
            String id = ctx.pathParam("id");
            Question q = repo.findById(id);
            ctx.contentType("application/json");
            if (q == null) {
                ctx.status(404);
                ctx.result(new JSONObject().put("error", "not found").toString());
                return;
            }
            JSONObject obj = new JSONObject();
            obj.put("id", q.getId());
            obj.put("section", q.getSection());
            obj.put("domain", q.getDomain());
            obj.put("skill", q.getSkill());
            obj.put("difficulty", q.getDifficulty());
            obj.put("question_type", q.getQuestionType());
            obj.put("stimulus", q.getStimulus());
            obj.put("stem", q.getStem());
            obj.put("choices", q.getChoicesJson() != null ? new JSONArray(q.getChoicesJson()) : JSONObject.NULL);
            obj.put("source", q.getSource());
            obj.put("parent_question_id", q.getParentQuestionId());
            ctx.result(obj.toString());
        });

        app.get("/api/questions/{id}/answer", ctx -> {
            String id = ctx.pathParam("id");
            Question q = repo.findById(id);
            ctx.contentType("application/json");
            if (q == null) {
                ctx.status(404);
                ctx.result(new JSONObject().put("error", "not found").toString());
                return;
            }
            JSONObject obj = new JSONObject();
            obj.put("id", q.getId());
            obj.put("correct_answer", q.getCorrectAnswer());
            obj.put("explanation", q.getExplanation());
            ctx.result(obj.toString());
        });

        app.get("/api/meta/filters", ctx -> {
            JSONObject obj = new JSONObject();
            obj.put("sections", new JSONArray(repo.distinctValues("section")));
            obj.put("domains", new JSONArray(repo.distinctValues("domain")));
            obj.put("skills", new JSONArray(repo.distinctValues("skill")));
            obj.put("difficulties", new JSONArray(repo.distinctValues("difficulty")));
            ctx.contentType("application/json");
            ctx.result(obj.toString());
        });

        app.start(port);
        return app;
    }

    public static void main(String[] args) throws SQLException {
        int port = resolvePort();
        Javalin app = start(Database.resolveDbPath(), port);
        System.out.println("Listening on http://localhost:" + port);
    }
}
