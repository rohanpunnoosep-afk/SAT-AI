package satapp.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import satapp.model.Question;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class OpenAiClient {

    // gpt-4o-mini could not reliably solve its own SAT math questions, which is what
    // produced wrong answers and self-contradicting explanations. The pipeline below
    // leans on the model actually being able to solve the question it just wrote.
    private static final String DEFAULT_MODEL = "gpt-5.4-mini";
    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    // gpt-5.4-mini defaults reasoning_effort to "none", so until now every call -- the
    // writer's and both blind solvers' -- attacked Hard Advanced Math with no reasoning
    // at all. That is the single largest cause of wrong answers here. Effort is set per
    // role instead: the solvers are the safety net and get the most, the writer enough to
    // construct a consistent question, the distractor call almost none since the answer
    // is already fixed by then.
    private static final String DEFAULT_WRITER_EFFORT = "medium";
    private static final String DEFAULT_VALIDATOR_EFFORT = "high";
    private static final String DEFAULT_DISTRACTOR_EFFORT = "low";

    /** Effort is mutually exclusive with temperature, so "none" means "sample instead". */
    static final String EFFORT_NONE = "none";

    private static final List<String> VALID_EFFORTS =
        List.of(EFFORT_NONE, "low", "medium", "high", "xhigh");

    public static boolean isConfigured() {
        String key = System.getenv("OPENAI_API_KEY");
        return key != null && !key.trim().isEmpty();
    }

    public static String model() {
        String model = System.getenv("OPENAI_MODEL");
        return (model != null && !model.trim().isEmpty()) ? model.trim() : DEFAULT_MODEL;
    }

    public static String baseUrl() {
        String base = System.getenv("OPENAI_BASE_URL");
        return (base != null && !base.trim().isEmpty()) ? base.trim() : DEFAULT_BASE_URL;
    }

    public static boolean validationEnabled() {
        String value = System.getenv("SAT_VALIDATE_ANSWERS");
        if (value == null) {
            return true;
        }
        String normalized = value.trim().toLowerCase();
        return !(normalized.equals("off") || normalized.equals("false") || normalized.equals("0"));
    }

    private static final int MAX_ATTEMPTS = 3;

    public static String writerEffort() {
        return effortFromEnv("SAT_WRITER_REASONING_EFFORT", DEFAULT_WRITER_EFFORT);
    }

    public static String validatorEffort() {
        return effortFromEnv("SAT_VALIDATOR_REASONING_EFFORT", DEFAULT_VALIDATOR_EFFORT);
    }

    public static String distractorEffort() {
        return effortFromEnv("SAT_DISTRACTOR_REASONING_EFFORT", DEFAULT_DISTRACTOR_EFFORT);
    }

    /** Falls back to the default rather than failing the run on a typo in the env var. */
    static String effortFromEnv(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase();
        if (!VALID_EFFORTS.contains(normalized)) {
            System.err.println(name + "=" + value + " is not one of " + VALID_EFFORTS
                + "; using " + fallback);
            return fallback;
        }
        return normalized;
    }

    public static String validatorModel() {
        String model = System.getenv("OPENAI_VALIDATOR_MODEL");
        return (model != null && !model.trim().isEmpty()) ? model.trim() : model();
    }

    public static JSONObject parseGeneratedQuestion(String rawModelText) throws GenerationException {
        if (rawModelText == null) {
            throw new GenerationException("Model reply was empty");
        }
        String text = stripCodeFence(rawModelText.trim());

        JSONObject obj;
        try {
            obj = new JSONObject(text);
        } catch (JSONException e) {
            throw new GenerationException("Model reply was not parseable JSON object");
        }

        String stem = obj.optString("stem", "").trim();
        if (stem.isEmpty()) {
            throw new GenerationException("Model reply missing non-blank stem");
        }

        String explanation = obj.optString("explanation", "").trim();
        if (explanation.isEmpty()) {
            throw new GenerationException("Model reply missing non-blank explanation");
        }

        JSONArray choicesArr = obj.optJSONArray("choices");
        if (choicesArr == null || choicesArr.length() < 2) {
            throw new GenerationException("Model reply missing at least 2 choices");
        }

        Set<String> seenIds = new HashSet<>();
        JSONArray validatedChoices = new JSONArray();
        for (int i = 0; i < choicesArr.length(); i++) {
            Object item = choicesArr.get(i);
            if (!(item instanceof JSONObject)) {
                throw new GenerationException("Choice entry was not a JSON object");
            }
            JSONObject choice = (JSONObject) item;
            String id = choice.optString("id", "").trim();
            String text2 = choice.optString("text", "").trim();
            if (id.isEmpty() || text2.isEmpty()) {
                throw new GenerationException("Choice missing non-blank id or text");
            }
            if (!seenIds.add(id.toLowerCase())) {
                throw new GenerationException("Duplicate choice id: " + id);
            }
            JSONObject cleanChoice = new JSONObject();
            cleanChoice.put("id", id);
            cleanChoice.put("text", text2);
            validatedChoices.put(cleanChoice);
        }

        String correctAnswer = obj.optString("correct_answer", "").trim();
        if (correctAnswer.isEmpty()) {
            throw new GenerationException("Model reply missing correct_answer");
        }
        boolean matched = false;
        for (String id : seenIds) {
            if (id.equalsIgnoreCase(correctAnswer)) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            throw new GenerationException("correct_answer does not match any choice id");
        }

        JSONObject result = new JSONObject();
        result.put("stem", stem);
        result.put("choices", validatedChoices);
        String work = obj.optString("work", "").trim();
        if (!work.isEmpty()) {
            result.put("work", work);
        }
        result.put("correct_answer", correctAnswer);
        result.put("explanation", explanation);
        return result;
    }

    private static String stripCodeFence(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline != -1) {
                text = text.substring(firstNewline + 1);
            }
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd != -1) {
                text = text.substring(0, fenceEnd);
            }
        }
        return text.trim();
    }

    public static String extractMessageContent(String chatCompletionsResponseBody) throws GenerationException {
        if (chatCompletionsResponseBody == null) {
            throw new GenerationException("Empty response body");
        }
        try {
            JSONObject root = new JSONObject(chatCompletionsResponseBody);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new GenerationException("Response body missing choices[0]");
            }
            JSONObject first = choices.getJSONObject(0);
            JSONObject message = first.optJSONObject("message");
            if (message == null) {
                throw new GenerationException("Response body missing choices[0].message");
            }
            String content = message.optString("content", null);
            if (content == null) {
                throw new GenerationException("Response body missing choices[0].message.content");
            }
            return content;
        } catch (JSONException e) {
            throw new GenerationException("Response body was not parseable JSON");
        }
    }

    public static JSONObject generate(Question seed) throws GenerationException {
        return generate(seed, QuestionGenPrompt.LEVEL_SAME_PROCEDURE);
    }

    /**
     * Produces a verified variant of {@code seed}.
     *
     * <p>Math questions go through an answer-first pipeline: the model writes the question
     * open-ended (no choices), two independent blind solvers are handed just the stem, and
     * the question is only kept when both solvers and the writer land on the same value.
     * Only then are distractors built around the confirmed answer. Reading and Writing
     * questions cannot be posed without their choices, so they keep the choices-first
     * shape and are blind-solved with the choices attached.
     */
    public static JSONObject generate(Question seed, int level) throws GenerationException {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.trim().isEmpty()) {
            throw new GenerationException("OPENAI_API_KEY is not set");
        }

        // "Which of the following must be an integer?" has no open-ended form: strip the
        // choices and several different expressions are equally correct, so the two blind
        // solvers name two of them, that reads as disagreement, and all three attempts are
        // rejected as ambiguous. Those seeds keep the choices-first pipeline even though
        // they are math.
        boolean answerFirst = AnswerValidator.isMathSection(seed)
            && !AnswerValidator.isChoiceDependent(seed);
        GenerationException lastError = null;
        String priorFailure = null;
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            try {
                if (answerFirst) {
                    return generateAnswerFirst(seed, level, key, priorFailure);
                }
                JSONObject generated = attempt(seed, level, key, priorFailure);
                validateOrThrow(generated, seed, key);
                return generated;
            } catch (GenerationException e) {
                lastError = e;
                priorFailure = e.getMessage();
                System.err.println("Generation attempt " + (i + 1) + " rejected: " + e.getMessage());
            }
        }
        throw lastError;
    }

    // ---------------------------------------------------------------- answer-first (math)

    private static JSONObject generateAnswerFirst(Question seed, int level, String key,
                                                  String priorFailure) throws GenerationException {
        String prompt = QuestionGenPrompt.buildOpenEnded(seed, level, priorFailure);
        JSONObject open = parseOpenEndedQuestion(
            chatCompletion(key, model(), writerEffort(), 0.8, prompt));

        String stem = open.getString("stem");
        String claimed = open.getString("answer");

        if (AnswerValidator.plainText(stem).equalsIgnoreCase(AnswerValidator.plainText(seed.getStem()))) {
            throw new GenerationException("generated stem is identical to the seed stem");
        }

        String verified = blindSolveOpen(stem, claimed, key);

        JSONObject result = new JSONObject();
        result.put("stem", stem);
        result.put("work", open.optString("work", ""));
        result.put("explanation", open.getString("explanation"));

        if ("spr".equalsIgnoreCase(seed.getQuestionType())) {
            // Student-produced response: the verified value is the answer, no choices needed.
            result.put("question_type", "spr");
            result.put("choices", new JSONArray());
            result.put("correct_answer", verified);
            result.put("explanation", withAnswerLine(result.getString("explanation"), null, verified));
            rejectIfProblems(result, seed);
            return result;
        }

        JSONArray choices = buildChoices(stem, verified, key);
        String correctId = AnswerValidator.idOfChoiceText(choices, verified);
        result.put("question_type", "mcq");
        result.put("choices", choices);
        result.put("correct_answer", correctId);
        result.put("explanation", withAnswerLine(result.getString("explanation"), correctId, verified));

        rejectIfProblems(result, seed);
        return result;
    }

    private static void rejectIfProblems(JSONObject generated, Question seed) throws GenerationException {
        // Only the answer-first walkthrough is checked for this: it is a pure solution
        // narrative, so "no such pair exists" there can only mean the stem is broken. A
        // choices-first explanation says the same words legitimately when ruling out a
        // distractor.
        String contradiction = AnswerValidator.selfContradiction(generated.optString("explanation", ""));
        if (contradiction != null) {
            throw new GenerationException("the explanation contradicts its own question (\""
                + contradiction + "\"), so the stem states a condition nothing satisfies");
        }

        List<String> problems = AnswerValidator.staticChecks(generated, seed);
        if (!problems.isEmpty()) {
            throw new GenerationException("generated question failed validation: "
                + String.join("; ", problems));
        }
    }

    /**
     * Hands the bare stem to two independent solvers and requires all three answers -- both
     * solvers and the writer's own -- to agree before the question is trusted. With no
     * choices in front of it a solver cannot pattern-match its way to the marked answer, so
     * agreement here is real evidence rather than an echo.
     */
    private static String blindSolveOpen(String stem, String claimed, String key)
            throws GenerationException {
        String prompt = AnswerValidator.buildOpenSolverPrompt(stem);
        String effort = validatorEffort();
        // With reasoning on, temperature is not accepted, so the two solvers are separated
        // by ordinary sampling variation rather than by a temperature spread. That is a
        // stronger independence check than it sounds: a question only survives when two
        // full reasoning passes land on the same value.
        List<String> replies = inParallel(
            () -> chatCompletion(key, validatorModel(), effort, 0.0, prompt),
            () -> chatCompletion(key, validatorModel(), effort, 0.3, prompt));

        if (!validationEnabled()) {
            return claimed;
        }
        if (replies.size() < 2) {
            System.err.println("Answer validator call failed, degrading to unvalidated");
            return claimed;
        }

        String first = AnswerValidator.parseOpenSolverAnswer(replies.get(0));
        String second = AnswerValidator.parseOpenSolverAnswer(replies.get(1));

        // A solver that reports the stem unsolvable is the only signal that catches a
        // question whose conditions contradict each other. Both solvers "repairing" the
        // same broken stem otherwise looks exactly like agreement.
        if (AnswerValidator.isInconsistent(first) || AnswerValidator.isInconsistent(second)) {
            throw new GenerationException("a blind solver reported the question as written is "
                + "unsolvable or ambiguous, so the stem contradicts itself");
        }
        if (!AnswerValidator.answersMatch(first, second)) {
            throw new GenerationException("the two blind solvers disagreed (" + first + " vs "
                + second + "), so the question is ambiguous");
        }
        if (!AnswerValidator.answersMatch(first, claimed)) {
            throw new GenerationException("answer validation failed: blind solvers got " + first
                + " but the question was written with answer " + claimed);
        }
        // Keep the writer's spelling of the answer; it matches the explanation's wording.
        return claimed;
    }

    private static JSONArray buildChoices(String stem, String answer, String key)
            throws GenerationException {
        String prompt = QuestionGenPrompt.buildDistractors(stem, answer);
        String content = chatCompletion(key, model(), distractorEffort(), 0.7, prompt);
        JSONObject obj;
        try {
            obj = new JSONObject(stripCodeFence(content.trim()));
        } catch (JSONException e) {
            throw new GenerationException("distractor reply was not parseable JSON object");
        }
        JSONArray arr = obj.optJSONArray("distractors");
        if (arr == null || arr.length() < 3) {
            throw new GenerationException("distractor reply did not contain 3 distractors");
        }
        List<String> distractors = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String text = arr.optString(i, "").trim();
            if (text.isEmpty() || AnswerValidator.containsMarkup(text)) {
                throw new GenerationException("distractor was blank or contained markup");
            }
            distractors.add(text);
        }
        return AnswerValidator.assembleChoices(answer, distractors);
    }

    /** Makes the explanation state the verified answer, so it can never contradict it. */
    private static String withAnswerLine(String explanation, String choiceId, String answer) {
        String suffix = choiceId == null
            ? "\n\nAnswer: " + answer
            : "\n\nAnswer: " + choiceId + " (" + answer + ")";
        return explanation.trim() + suffix;
    }

    static JSONObject parseOpenEndedQuestion(String rawModelText) throws GenerationException {
        if (rawModelText == null) {
            throw new GenerationException("Model reply was empty");
        }
        JSONObject obj;
        try {
            obj = new JSONObject(stripCodeFence(rawModelText.trim()));
        } catch (JSONException e) {
            throw new GenerationException("Model reply was not parseable JSON object");
        }
        JSONObject result = new JSONObject();
        for (String field : new String[]{"stem", "answer", "explanation"}) {
            String value = obj.optString(field, "").trim();
            if (value.isEmpty()) {
                throw new GenerationException("Model reply missing non-blank " + field);
            }
            if (AnswerValidator.containsMarkup(value)) {
                throw new GenerationException("Model reply contained markup in " + field);
            }
            result.put(field, value);
        }
        result.put("work", obj.optString("work", "").trim());
        return result;
    }

    /** Runs the calls concurrently; returns only the ones that succeeded, in order. */
    private static List<String> inParallel(Callable<String> first, Callable<String> second) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(first);
            Future<String> b = pool.submit(second);
            List<String> results = new ArrayList<>();
            for (Future<String> future : List.of(a, b)) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    System.err.println("Validator call failed: " + e.getMessage());
                }
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------- choices-first (reading/writing)

    private static JSONObject attempt(Question seed, int level, String key, String priorFailure)
            throws GenerationException {
        String prompt = QuestionGenPrompt.build(seed, level, priorFailure);
        String content = chatCompletion(key, model(), writerEffort(), 0.8, prompt);
        return parseGeneratedQuestion(content);
    }

    private static void validateOrThrow(JSONObject generated, Question seed, String key) throws GenerationException {
        String canonicalAnswer = AnswerValidator.normalizeCorrectAnswer(generated);
        generated.put("correct_answer", canonicalAnswer);

        List<String> problems = AnswerValidator.staticChecks(generated, seed);
        if (!problems.isEmpty()) {
            throw new GenerationException("generated question failed validation: " + String.join("; ", problems));
        }

        if (!validationEnabled()) {
            return;
        }

        JSONArray choices = generated.getJSONArray("choices");
        String prompt = AnswerValidator.buildSolverPrompt(generated.getString("stem"), choices);
        String effort = validatorEffort();
        List<String> replies = inParallel(
            () -> chatCompletion(key, validatorModel(), effort, 0.0, prompt),
            () -> chatCompletion(key, validatorModel(), effort, 0.3, prompt));
        if (replies.size() < 2) {
            System.err.println("Answer validator call failed, degrading to unvalidated");
            return;
        }

        String claimed = generated.getString("correct_answer");
        String first = AnswerValidator.parseSolverAnswer(replies.get(0), choices);
        String second = AnswerValidator.parseSolverAnswer(replies.get(1), choices);

        if (AnswerValidator.isInconsistent(first) || AnswerValidator.isInconsistent(second)) {
            throw new GenerationException("a blind solver reported the question as written has no "
                + "single defensible choice, so the question is broken or ambiguous");
        }
        if (AnswerValidator.agrees(first, claimed) && AnswerValidator.agrees(second, claimed)) {
            return;
        }
        throw new GenerationException("answer validation failed: blind solvers chose " + first
            + "/" + second + " but the question was marked " + claimed);
    }

    /**
     * One chat-completions call. {@code effort} and {@code temperature} are mutually
     * exclusive on the GPT-5.x models: the API rejects temperature whenever
     * reasoning_effort is anything but "none", so exactly one of the two is sent.
     */
    private static String chatCompletion(String key, String model, String effort,
                                         double temperature, String prompt)
            throws GenerationException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        if (effort == null || EFFORT_NONE.equals(effort)) {
            requestBody.put("temperature", temperature);
        } else {
            requestBody.put("reasoning_effort", effort);
        }
        JSONArray messages = new JSONArray();
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.put(userMessage);
        requestBody.put("messages", messages);

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/v1/chat/completions"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GenerationException("OpenAI request failed: " + e.getClass().getSimpleName());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GenerationException("OpenAI request returned status " + response.statusCode());
        }

        return extractMessageContent(response.body());
    }
}
