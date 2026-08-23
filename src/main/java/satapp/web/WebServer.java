package satapp.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.staticfiles.Location;
import org.json.JSONArray;
import org.json.JSONObject;
import satapp.ai.GenerationException;
import satapp.ai.OpenAiClient;
import satapp.ai.QuestionGenPrompt;
import satapp.db.BoxRepository;
import satapp.db.Database;
import satapp.db.QuestionRepository;
import satapp.model.Box;
import satapp.model.Question;
import satapp.model.TopicStat;
import satapp.session.AnswerGrader;
import satapp.session.SessionState;
import satapp.session.SessionStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
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
        BoxRepository boxRepo = new BoxRepository(conn);
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
                obj.put("cb_question_id", q.getCbQuestionId() != null ? q.getCbQuestionId() : JSONObject.NULL);
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
            ctx.result(questionDetailJson(q).toString());
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

        // ---- AI question generation ----

        app.post("/api/questions/{id}/generate", ctx -> {
            String id = ctx.pathParam("id");
            ctx.contentType("application/json");

            Question seed = repo.findById(id);
            if (seed == null) {
                ctx.status(404);
                ctx.result(new JSONObject().put("error", "not found").toString());
                return;
            }

            if (!OpenAiClient.isConfigured()) {
                ctx.status(503);
                ctx.result(new JSONObject()
                    .put("error", "AI generation unavailable: OPENAI_API_KEY is not set").toString());
                return;
            }

            int level = parseLevel(ctx.queryParam("level"));

            JSONObject generated;
            try {
                generated = OpenAiClient.generate(seed, level);
            } catch (GenerationException e) {
                ctx.status(502);
                ctx.result(new JSONObject().put("error", e.getMessage()).toString());
                return;
            }

            Question newQuestion = questionFromGenerated(seed, generated);
            repo.insert(newQuestion);

            ctx.status(201);
            ctx.result(questionDetailJson(newQuestion).toString());
        });

        // ---- Boxes (saved question collections) ----

        app.get("/api/boxes", ctx -> {
            List<Box> boxes = boxRepo.listAll();
            JSONArray arr = new JSONArray();
            for (Box box : boxes) {
                JSONObject obj = new JSONObject();
                obj.put("id", box.getId());
                obj.put("label", box.getLabel());
                obj.put("question_count", boxRepo.countQuestions(box.getId()));
                arr.put(obj);
            }
            ctx.contentType("application/json");
            ctx.result(arr.toString());
        });

        app.post("/api/boxes", ctx -> {
            ctx.contentType("application/json");
            JSONObject body;
            try {
                body = new JSONObject(ctx.body());
            } catch (Exception e) {
                ctx.status(400);
                ctx.result(new JSONObject().put("error", "invalid request body").toString());
                return;
            }
            String label = body.optString("label", "").trim();
            if (label.isEmpty()) {
                ctx.status(400);
                ctx.result(new JSONObject().put("error", "label is required").toString());
                return;
            }
            Box box = boxRepo.create("box-" + UUID.randomUUID(), label);
            JSONObject obj = new JSONObject();
            obj.put("id", box.getId());
            obj.put("label", box.getLabel());
            obj.put("question_count", 0);
            ctx.status(201);
            ctx.result(obj.toString());
        });

        app.post("/api/boxes/{boxId}/questions", ctx -> {
            ctx.contentType("application/json");
            String boxId = ctx.pathParam("boxId");
            Box box = boxRepo.findById(boxId);
            if (box == null) {
                ctx.status(404);
                ctx.result(new JSONObject().put("error", "box not found").toString());
                return;
            }
            JSONObject body;
            try {
                body = new JSONObject(ctx.body());
            } catch (Exception e) {
                ctx.status(400);
                ctx.result(new JSONObject().put("error", "invalid request body").toString());
                return;
            }
            String questionId = body.optString("questionId", "").trim();
            if (questionId.isEmpty()) {
                ctx.status(400);
                ctx.result(new JSONObject().put("error", "questionId is required").toString());
                return;
            }
            Question q = repo.findById(questionId);
            if (q == null) {
                ctx.status(404);
                ctx.result(new JSONObject().put("error", "question not found").toString());
                return;
            }
            boxRepo.addQuestion(boxId, questionId);
            ctx.status(204);
            ctx.result("");
        });

        app.delete("/api/boxes/{boxId}/questions/{questionId}", ctx -> {
            String boxId = ctx.pathParam("boxId");
            String questionId = ctx.pathParam("questionId");
            boxRepo.removeQuestion(boxId, questionId);
            ctx.status(204);
            ctx.result("");
        });

        app.get("/api/boxes/{boxId}/questions", ctx -> {
            ctx.contentType("application/json");
            String boxId = ctx.pathParam("boxId");
            Box box = boxRepo.findById(boxId);
            if (box == null) {
                ctx.status(404);
                ctx.result(new JSONObject().put("error", "box not found").toString());
                return;
            }
            List<Question> questions = boxRepo.listQuestions(boxId);
            JSONArray arr = new JSONArray();
            for (Question q : questions) {
                JSONObject obj = new JSONObject();
                obj.put("id", q.getId());
                obj.put("cb_question_id", q.getCbQuestionId() != null ? q.getCbQuestionId() : JSONObject.NULL);
                obj.put("section", q.getSection());
                obj.put("domain", q.getDomain());
                obj.put("skill", q.getSkill());
                obj.put("difficulty", q.getDifficulty());
                obj.put("question_type", q.getQuestionType());
                obj.put("source", q.getSource());
                arr.put(obj);
            }
            ctx.result(arr.toString());
        });

        // Generates `count` similar questions from a box's seed questions (round-robin
        // across the box's questions), at the requested similarity level, and inserts
        // them as new questions rooted at their seed via parent_question_id.
        app.post("/api/boxes/{boxId}/generate", ctx -> {
            ctx.contentType("application/json");
            String boxId = ctx.pathParam("boxId");
            Box box = boxRepo.findById(boxId);
            if (box == null) {
                ctx.status(404);
                ctx.result(new JSONObject().put("error", "box not found").toString());
                return;
            }
            // Seed only from the box's own saved questions -- generated variants live in
            // the same box now, and generating from a generated question compounds drift.
            List<Question> seeds = new ArrayList<>();
            for (Question q : boxRepo.listQuestions(boxId)) {
                if (!"ai_generated".equals(q.getSource())) {
                    seeds.add(q);
                }
            }
            if (seeds.isEmpty()) {
                ctx.status(400);
                ctx.result(new JSONObject().put("error", "box has no questions to generate from").toString());
                return;
            }
            if (!OpenAiClient.isConfigured()) {
                ctx.status(503);
                ctx.result(new JSONObject()
                    .put("error", "AI generation unavailable: OPENAI_API_KEY is not set").toString());
                return;
            }

            JSONObject body = new JSONObject();
            if (ctx.body() != null && !ctx.body().trim().isEmpty()) {
                try {
                    body = new JSONObject(ctx.body());
                } catch (Exception e) {
                    ctx.status(400);
                    ctx.result(new JSONObject().put("error", "invalid request body").toString());
                    return;
                }
            }
            int count = body.optInt("count", 1);
            if (count < 1) {
                count = 1;
            }
            if (count > 20) {
                count = 20;
            }
            int level = parseLevel(body.has("level") ? String.valueOf(body.optInt("level")) : null);

            JSONArray generatedArr = new JSONArray();
            List<String> failures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Question seed = seeds.get(i % seeds.size());
                try {
                    JSONObject generated = OpenAiClient.generate(seed, level);
                    Question newQuestion = questionFromGenerated(seed, generated);
                    repo.insert(newQuestion);
                    // File the variant back into the box it was generated from, otherwise
                    // it is stranded in the questions table with no way to reach it.
                    boxRepo.addQuestion(boxId, newQuestion.getId());
                    generatedArr.put(questionDetailJson(newQuestion));
                } catch (GenerationException e) {
                    failures.add(e.getMessage());
                }
            }

            JSONObject resp = new JSONObject();
            resp.put("questions", generatedArr);
            resp.put("requested", count);
            resp.put("generated", generatedArr.length());
            if (!failures.isEmpty()) {
                resp.put("failures", new JSONArray(failures));
            }
            ctx.status(generatedArr.length() > 0 ? 201 : 502);
            ctx.result(resp.toString());
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
            // Every miss is filed into the standing "Missed Questions" box so the box can
            // later be used as a generation seed set without any manual bookkeeping.
            if (!correct) {
                Box missed = missedQuestionsBox(boxRepo);
                boxRepo.addQuestion(missed.getId(), questionId);
                resp.put("missed_box_id", missed.getId());
                resp.put("missed_box_label", missed.getLabel());
            }
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

    /** The auto-managed box that collects every question answered incorrectly. */
    static final String MISSED_BOX_LABEL = "Missed Questions";

    private static Box missedQuestionsBox(BoxRepository boxRepo) throws SQLException {
        Box existing = boxRepo.findByLabel(MISSED_BOX_LABEL);
        if (existing != null) {
            return existing;
        }
        return boxRepo.create("box-missed", MISSED_BOX_LABEL);
    }

    private static int parseLevel(String levelParam) {
        if (levelParam != null) {
            try {
                int level = Integer.parseInt(levelParam.trim());
                if (level == QuestionGenPrompt.LEVEL_SAME_TECHNIQUE) {
                    return QuestionGenPrompt.LEVEL_SAME_TECHNIQUE;
                }
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return QuestionGenPrompt.LEVEL_SAME_PROCEDURE;
    }

    private static Question questionFromGenerated(Question seed, JSONObject generated) {
        Question newQuestion = new Question();
        newQuestion.setId("ai-" + UUID.randomUUID());
        newQuestion.setExternalId(null);
        newQuestion.setCbQuestionId(null);
        newQuestion.setSection(seed.getSection());
        newQuestion.setDomain(seed.getDomain());
        newQuestion.setSkill(seed.getSkill());
        newQuestion.setDifficulty(seed.getDifficulty());
        newQuestion.setQuestionType("mcq");
        newQuestion.setStimulus(null);
        newQuestion.setStem(generated.getString("stem"));
        newQuestion.setChoicesJson(generated.getJSONArray("choices").toString());
        newQuestion.setCorrectAnswer(generated.getString("correct_answer"));
        newQuestion.setExplanation(generated.getString("explanation"));
        newQuestion.setSource("ai_generated");
        newQuestion.setParentQuestionId(seed.getId());
        return newQuestion;
    }

    private static JSONObject questionDetailJson(Question q) {
        JSONObject obj = new JSONObject();
        obj.put("id", q.getId());
        obj.put("cb_question_id", q.getCbQuestionId() != null ? q.getCbQuestionId() : JSONObject.NULL);
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
        return obj;
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
