package satapp.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import satapp.model.Question;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnswerValidator {

    private static final Pattern FINAL_LINE = Pattern.compile(
        "(?i)FINAL\\s*:\\s*([A-Za-z0-9]+)");

    public static String normalizeCorrectAnswer(JSONObject generated) throws GenerationException {
        String correctAnswer = generated.optString("correct_answer", "").trim();
        JSONArray choices = generated.optJSONArray("choices");

        if (choices != null) {
            for (int i = 0; i < choices.length(); i++) {
                JSONObject choice = choices.getJSONObject(i);
                String id = choice.optString("id", "");
                if (id.equalsIgnoreCase(correctAnswer)) {
                    return id;
                }
            }
            for (int i = 0; i < choices.length(); i++) {
                JSONObject choice = choices.getJSONObject(i);
                String text = choice.optString("text", "").trim();
                if (text.equalsIgnoreCase(correctAnswer)) {
                    return choice.optString("id", "");
                }
            }
        }

        throw new GenerationException("correct_answer does not match any choice");
    }

    public static List<String> staticChecks(JSONObject generated, Question seed) {
        List<String> problems = new ArrayList<>();

        JSONArray choices = generated.optJSONArray("choices");
        if (choices != null) {
            List<String> seenTexts = new ArrayList<>();
            for (int i = 0; i < choices.length(); i++) {
                JSONObject choice = choices.getJSONObject(i);
                String normalized = normalizeWhitespace(choice.optString("text", ""));
                if (seenTexts.contains(normalized.toLowerCase())) {
                    problems.add("duplicate choice text: " + choice.optString("text", ""));
                } else {
                    seenTexts.add(normalized.toLowerCase());
                }
            }
        }

        String explanation = generated.optString("explanation", "");
        String correctAnswer = generated.optString("correct_answer", "").trim();
        if (choices != null && !correctAnswer.isEmpty()) {
            String correctText = null;
            for (int i = 0; i < choices.length(); i++) {
                JSONObject choice = choices.getJSONObject(i);
                String id = choice.optString("id", "");
                if (id.equalsIgnoreCase(correctAnswer)) {
                    correctText = choice.optString("text", "");
                    break;
                }
            }
            boolean referencesId = false;
            if (!correctAnswer.isEmpty()) {
                Pattern idPattern = Pattern.compile(
                    "(?i)\\b" + Pattern.quote(correctAnswer) + "\\b");
                referencesId = idPattern.matcher(explanation).find();
            }
            boolean referencesText = correctText != null && !correctText.trim().isEmpty()
                && explanation.toLowerCase().contains(correctText.trim().toLowerCase());
            if (!referencesId && !referencesText) {
                problems.add("explanation does not reference the marked correct choice");
            }
        }

        if (seed != null && seed.getStem() != null) {
            String generatedStem = normalizeWhitespace(generated.optString("stem", ""));
            String seedStem = normalizeWhitespace(seed.getStem());
            if (!generatedStem.isEmpty() && generatedStem.equalsIgnoreCase(seedStem)) {
                problems.add("generated stem is identical to the seed stem");
            }
        }

        return problems;
    }

    public static String buildSolverPrompt(String stem, JSONArray choices) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert SAT test taker.\n");
        sb.append("Solve the question below. Work through it step by step FIRST, ")
          .append("then state your final answer.\n\n");
        sb.append("Question: ").append(stem).append("\n");
        for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.getJSONObject(i);
            sb.append(choice.optString("id", "")).append(". ")
              .append(choice.optString("text", "")).append("\n");
        }
        sb.append("\nYour reply MUST end with a final line in exactly this form, ")
          .append("and nothing after it:\n");
        sb.append("FINAL: <choice id>\n");
        return sb.toString();
    }

    public static String parseSolverAnswer(String reply, JSONArray choices) throws GenerationException {
        if (reply == null) {
            throw new GenerationException("solver reply did not contain a usable FINAL answer");
        }

        String lastToken = null;
        Matcher matcher = FINAL_LINE.matcher(reply);
        while (matcher.find()) {
            lastToken = matcher.group(1);
        }

        if (lastToken != null) {
            for (int i = 0; i < choices.length(); i++) {
                JSONObject choice = choices.getJSONObject(i);
                String id = choice.optString("id", "");
                if (id.equalsIgnoreCase(lastToken)) {
                    return id;
                }
            }
        }

        throw new GenerationException("solver reply did not contain a usable FINAL answer");
    }

    public static boolean agrees(String solverAnswer, String claimedAnswer) {
        if (solverAnswer == null || claimedAnswer == null) {
            return false;
        }
        return solverAnswer.trim().equalsIgnoreCase(claimedAnswer.trim());
    }

    private static String normalizeWhitespace(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }
}
