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
import java.util.HashSet;
import java.util.Set;

public class OpenAiClient {

    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

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
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.trim().isEmpty()) {
            throw new GenerationException("OPENAI_API_KEY is not set");
        }

        GenerationException lastError;
        try {
            return attempt(seed, key, false);
        } catch (GenerationException first) {
            lastError = first;
        }

        try {
            return attempt(seed, key, true);
        } catch (GenerationException second) {
            throw second;
        }
    }

    private static JSONObject attempt(Question seed, String key, boolean stricterRetry) throws GenerationException {
        String prompt = QuestionGenPrompt.build(seed, stricterRetry);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model());
        requestBody.put("temperature", 0.8);
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

        String content = extractMessageContent(response.body());
        return parseGeneratedQuestion(content);
    }
}
