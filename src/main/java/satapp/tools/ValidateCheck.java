package satapp.tools;

import org.json.JSONArray;
import org.json.JSONObject;
import satapp.ai.AnswerValidator;
import satapp.ai.GenerationException;
import satapp.model.Question;

import java.util.List;

public class ValidateCheck {

    private static boolean allOk = true;

    public static void main(String[] args) {
        JSONObject cleanGenerated = generated(
            "What is 2+2?",
            new String[][]{{"A", "3"}, {"B", "4"}, {"C", "5"}},
            "B",
            "The correct choice is B because 2+2=4.");

        // normalizeCorrectAnswer
        expectNormalize("normalizeCorrectAnswer returns canonical id for lowercase id",
            cleanGenerated, "B");

        JSONObject byText = generated(
            "What is 2+2?",
            new String[][]{{"A", "3"}, {"B", "4"}, {"C", "5"}},
            "4",
            "The correct choice is B because 2+2=4.");
        expectNormalize("normalizeCorrectAnswer maps choice text back to its id", byText, "B");

        JSONObject noMatch = generated(
            "What is 2+2?",
            new String[][]{{"A", "3"}, {"B", "4"}, {"C", "5"}},
            "Z",
            "x");
        expectNormalizeThrows("normalizeCorrectAnswer throws when answer matches nothing", noMatch);

        // staticChecks
        List<String> cleanProblems = AnswerValidator.staticChecks(cleanGenerated, null);
        expect("staticChecks returns empty for a clean question", cleanProblems.isEmpty());

        JSONObject dupTexts = generated(
            "What is 2+2?",
            new String[][]{{"A", "4"}, {"B", "  4 "}, {"C", "5"}},
            "A",
            "The correct choice is A.");
        List<String> dupProblems = AnswerValidator.staticChecks(dupTexts, null);
        expect("staticChecks flags duplicate choice texts (case/whitespace insensitive)",
            containsSubstring(dupProblems, "duplicate choice text"));

        JSONObject wrongExplanation = generated(
            "What is 2+2?",
            new String[][]{{"A", "3"}, {"B", "4"}, {"C", "5"}},
            "B",
            "The correct choice is A because that's what I think.");
        List<String> explProblems = AnswerValidator.staticChecks(wrongExplanation, null);
        expect("staticChecks flags explanation concluding with a different choice",
            containsSubstring(explProblems, "explanation does not reference"));

        Question seed = new Question();
        seed.setStem("What is 2+2?");
        List<String> identicalStemProblems = AnswerValidator.staticChecks(cleanGenerated, seed);
        expect("staticChecks flags a generated stem identical to the seed's stem",
            containsSubstring(identicalStemProblems, "identical to the seed stem"));

        Question differentSeed = new Question();
        differentSeed.setStem("What is 3+3?");
        List<String> differentStemProblems = AnswerValidator.staticChecks(cleanGenerated, differentSeed);
        expect("staticChecks is clean when the stem differs from the seed",
            !containsSubstring(differentStemProblems, "identical to the seed stem"));

        try {
            AnswerValidator.staticChecks(cleanGenerated, null);
            System.out.println("PASS: staticChecks does not throw when seed is null");
        } catch (Exception e) {
            System.out.println("FAIL: staticChecks threw when seed was null: " + e.getMessage());
            allOk = false;
        }

        // buildSolverPrompt
        JSONArray choices = cleanGenerated.getJSONArray("choices");
        String prompt = AnswerValidator.buildSolverPrompt(cleanGenerated.getString("stem"), choices);
        boolean hasStem = prompt.contains("What is 2+2?");
        boolean hasAllIds = prompt.contains("A.") && prompt.contains("B.") && prompt.contains("C.");
        boolean noCorrectWord = !prompt.toLowerCase().contains("correct");
        expect("buildSolverPrompt contains the stem", hasStem);
        expect("buildSolverPrompt contains every choice id", hasAllIds);
        expect("buildSolverPrompt does not contain the word 'correct'", noCorrectWord);

        // parseSolverAnswer
        expectParse("parseSolverAnswer handles plain FINAL: B", "reasoning...\nFINAL: B", choices, "B");
        expectParse("parseSolverAnswer handles bolded **FINAL: B**", "reasoning...\n**FINAL: B**", choices, "B");
        expectParse("parseSolverAnswer handles trailing-period FINAL: B.", "reasoning...\nFINAL: B.", choices, "B");
        expectParse("parseSolverAnswer picks the LAST FINAL line",
            "FINAL: A\nmore reasoning\nFINAL: C", choices, "C");

        try {
            AnswerValidator.parseSolverAnswer("no final line here", choices);
            System.out.println("FAIL: parseSolverAnswer should throw when there is no FINAL line");
            allOk = false;
        } catch (GenerationException e) {
            System.out.println("PASS: parseSolverAnswer throws when there is no FINAL line");
        }

        if (allOk) {
            System.out.println("VALIDATE_OK");
        } else {
            System.exit(1);
        }
    }

    private static JSONObject generated(String stem, String[][] choicePairs, String correctAnswer,
                                         String explanation) {
        JSONObject obj = new JSONObject();
        obj.put("stem", stem);
        JSONArray choices = new JSONArray();
        for (String[] pair : choicePairs) {
            JSONObject choice = new JSONObject();
            choice.put("id", pair[0]);
            choice.put("text", pair[1]);
            choices.put(choice);
        }
        obj.put("choices", choices);
        obj.put("correct_answer", correctAnswer);
        obj.put("explanation", explanation);
        return obj;
    }

    private static boolean containsSubstring(List<String> problems, String substring) {
        for (String problem : problems) {
            if (problem.contains(substring)) {
                return true;
            }
        }
        return false;
    }

    private static void expect(String label, boolean condition) {
        if (condition) {
            System.out.println("PASS: " + label);
        } else {
            System.out.println("FAIL: " + label);
            allOk = false;
        }
    }

    private static void expectNormalize(String label, JSONObject generated, String expectedId) {
        try {
            String result = AnswerValidator.normalizeCorrectAnswer(generated);
            if (expectedId.equals(result)) {
                System.out.println("PASS: " + label);
            } else {
                System.out.println("FAIL: " + label + " (got: " + result + ")");
                allOk = false;
            }
        } catch (GenerationException e) {
            System.out.println("FAIL: " + label + " (threw: " + e.getMessage() + ")");
            allOk = false;
        }
    }

    private static void expectNormalizeThrows(String label, JSONObject generated) {
        try {
            AnswerValidator.normalizeCorrectAnswer(generated);
            System.out.println("FAIL: " + label + " (did not throw)");
            allOk = false;
        } catch (GenerationException e) {
            System.out.println("PASS: " + label);
        }
    }

    private static void expectParse(String label, String reply, JSONArray choices, String expectedId) {
        try {
            String result = AnswerValidator.parseSolverAnswer(reply, choices);
            if (expectedId.equals(result)) {
                System.out.println("PASS: " + label);
            } else {
                System.out.println("FAIL: " + label + " (got: " + result + ")");
                allOk = false;
            }
        } catch (GenerationException e) {
            System.out.println("FAIL: " + label + " (threw: " + e.getMessage() + ")");
            allOk = false;
        }
    }
}
