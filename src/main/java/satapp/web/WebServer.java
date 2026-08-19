package satapp.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.staticfiles.Location;
import org.json.JSONArray;
import org.json.JSONObject;
import satapp.db.Database;
import satapp.db.QuestionRepository;
import satapp.model.Question;
import satapp.model.TopicStat;
import satapp.session.AnswerGrader;
import satapp.session.SessionState;
import satapp.session.SessionStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

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
        SessionStore sessionStore = new SessionStore();

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

        // ---- Session tracking (in-memory, per-browser, disposable) ----

        app.post("/api/session/answer", ctx -> {
            SessionState session = resolveSession(ctx, sessionStore);
            JSONObject body;
            try {
                body = new JSONObject(ctx.body());
            } catch (Exception e) {
                ctx.status(400);
                ctx.contentType("application/json");
                ctx.result(new JSONObject().put("error", "invalid request body").toString());
                return;
            }
            String questionId = body.optString("questionId", null);
            String submitted = body.has("submitted") ? body.optString("submitted", null)
                    : body.optString("selectedChoice", null);

            ctx.contentType("application/json");
            if (questionId == null || questionId.trim().isEmpty()) {
                ctx.status(400);
                ctx.result(new JSONObject().put("error", "questionId is required").toString());
                return;
            }
            if (submitted == null || submitted.trim().isEmpty()) {
                ctx.status(400);
                ctx.result(new JSONObject().put("error", "submitted is required").toString());
                return;
            }

            Question q = repo.findById(questionId);
            if (q == null) {
                ctx.status(404);
                ctx.result(new JSONObject().put("error", "not found").toString());
                return;
            }

            boolean correct = AnswerGrader.isCorrect(q, submitted);
            session.record(q, submitted, correct);

            JSONObject resp = new JSONObject();
            resp.put("correct", correct);
            resp.put("questionId", questionId);
            ctx.result(resp.toString());
        });

        app.get("/api/session/review-list", ctx -> {
            SessionState session = resolveSession(ctx, sessionStore);
            JSONArray arr = new JSONArray();
            for (TopicStat stat : session.reviewList()) {
                arr.put(topicStatJson(stat));
            }
            ctx.contentType("application/json");
            ctx.result(new JSONObject().put("topics", arr).toString());
        });

        app.get("/api/session/stats", ctx -> {
            SessionState session = resolveSession(ctx, sessionStore);
            int answered = session.getAnsweredQuestions().size();
            int correct = 0;
            for (SessionState.AnswerRecord record : session.getAnsweredQuestions().values()) {
                if (record.correct) {
                    correct++;
                }
            }
            JSONArray arr = new JSONArray();
            for (TopicStat stat : session.allTopics()) {
                arr.put(topicStatJson(stat));
            }
            JSONObject resp = new JSONObject();
            resp.put("answered", answered);
            resp.put("correct", correct);
            resp.put("topics", arr);
            ctx.contentType("application/json");
            ctx.result(resp.toString());
        });

        app.start(port);
        return app;
    }

    private static JSONObject topicStatJson(TopicStat stat) {
        JSONObject obj = new JSONObject();
        obj.put("domain", stat.getDomain());
        obj.put("skill", stat.getSkill());
        obj.put("attempts", stat.getAttempts());
        obj.put("correct", stat.getCorrect());
        obj.put("accuracy", stat.accuracy());
        return obj;
    }

    private static SessionState resolveSession(Context ctx, SessionStore sessionStore) {
        String sessionId = ctx.cookie(SessionStore.COOKIE);
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = UUID.randomUUID().toString();
            Cookie cookie = new Cookie(SessionStore.COOKIE, sessionId);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            ctx.cookie(cookie);
        }
        return sessionStore.get(sessionId);
    }

    public static void main(String[] args) throws SQLException {
        int port = resolvePort();
        Javalin app = start(Database.resolveDbPath(), port);
        System.out.println("Listening on http://localhost:" + port);
    }
}
