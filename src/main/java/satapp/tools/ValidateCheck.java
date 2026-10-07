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


        // ---- plainText: College Board markup must flatten for prompts and comparisons ----
        expect("plainText unwraps a <p> wrapper",
            "21,500".equals(AnswerValidator.plainText(
                "<p class=\"choice_paragraph \">21,500</p>")));
        expect("plainText prefers the alttext of a MathML expression",
            AnswerValidator.plainText(
                "<p><math alttext=\"p equals 20,000\"><mi>p</mi><mn>20000</mn></math></p>")
                .equals("p equals 20,000"));
        expect("plainText decodes entities",
            "a < b & c".equals(AnswerValidator.plainText("a &lt; b &amp; c")));
        expect("plainText leaves plain text alone",
            "p = 8400(1.06)^x".equals(AnswerValidator.plainText("p = 8400(1.06)^x")));

        // ---- containsMarkup: generated output must be plain text ----
        expect("containsMarkup flags a tag", AnswerValidator.containsMarkup("<mn>4</mn>"));
        expect("containsMarkup allows plain math", !AnswerValidator.containsMarkup("x^2 - 7x + 12"));

        // ---- staticChecks: markup in generated output is itself a rejection reason ----
        JSONObject markupGenerated = generated(
            "<p>What is 2+2?</p>",
            new String[][]{{"A", "3"}, {"B", "4"}},
            "B",
            "The correct choice is B because 2+2=4.");
        expect("staticChecks flags markup in the generated stem",
            containsSubstring(AnswerValidator.staticChecks(markupGenerated, null),
                "markup"));

        // ---- staticChecks: the explanation check compares flattened text ----
        JSONObject markupChoiceText = generated(
            "What is 2+2?",
            new String[][]{{"A", "3"}, {"B", "<p>4</p>"}},
            "B",
            "Adding gives 4, so the answer is 4.");
        expect("staticChecks matches an explanation against flattened choice text",
            !containsSubstring(AnswerValidator.staticChecks(markupChoiceText, null),
                "explanation does not reference"));

        JSONObject leakedHeader = generated(
            "Domain: Information and Ideas Skill: Inferences Difficulty: Hard Passage: A historian...",
            new String[][]{{"A", "3"}, {"B", "4"}},
            "B",
            "The answer is B.");
        expect("staticChecks flags a stem that repeats the prompt's seed header",
            containsSubstring(AnswerValidator.staticChecks(leakedHeader, null), "seed header"));
        expect("staticChecks leaves an ordinary stem alone",
            !containsSubstring(AnswerValidator.staticChecks(cleanGenerated, null), "seed header"));

        // ---- isMathSection routes seeds to the answer-first pipeline ----
        Question mathSeed = new Question();
        mathSeed.setSection("Math");
        Question verbalSeed = new Question();
        verbalSeed.setSection("Reading and Writing");
        expect("isMathSection is true for Math", AnswerValidator.isMathSection(mathSeed));
        expect("isMathSection is false for Reading and Writing",
            !AnswerValidator.isMathSection(verbalSeed));
        expect("isMathSection is false for a null seed", !AnswerValidator.isMathSection(null));

        // ---- buildOpenSolverPrompt must not leak the answer or the choices ----
        String openPrompt = AnswerValidator.buildOpenSolverPrompt("What is 2+2?");
        expect("buildOpenSolverPrompt contains the stem", openPrompt.contains("What is 2+2?"));
        expect("buildOpenSolverPrompt asks for a FINAL line", openPrompt.contains("FINAL:"));

        // ---- parseOpenSolverAnswer reads a value, not just a letter ----
        expectParseOpen("parseOpenSolverAnswer reads a plain number",
            "Work here.\nFINAL: 39", "39");
        expectParseOpen("parseOpenSolverAnswer reads an expression",
            "FINAL: p = 8400(1.06)^x", "p = 8400(1.06)^x");
        expectParseOpen("parseOpenSolverAnswer strips bold and a trailing period",
            "**FINAL: 34/3.**", "34/3");
        expectParseOpen("parseOpenSolverAnswer picks the LAST FINAL line",
            "FINAL: 12\nOn reflection...\nFINAL: 15", "15");
        try {
            AnswerValidator.parseOpenSolverAnswer("no final line at all");
            System.out.println("FAIL: parseOpenSolverAnswer should throw without a FINAL line");
            allOk = false;
        } catch (GenerationException e) {
            System.out.println("PASS: parseOpenSolverAnswer throws without a FINAL line");
        }

        // ---- answersMatch: the agreement test the whole pipeline rests on ----
        expect("answersMatch is true for identical text", AnswerValidator.answersMatch("39", "39"));
        expect("answersMatch ignores spacing and commas",
            AnswerValidator.answersMatch("p = 8400 (1.06)^x", "p=8400(1.06)^x"));
        expect("answersMatch treats $1,200 and 1200 as equal",
            AnswerValidator.answersMatch("$1,200", "1200"));
        expect("answersMatch treats 1/2 and 0.5 as equal",
            AnswerValidator.answersMatch("1/2", "0.5"));
        expect("answersMatch treats 25% and 0.25 as equal",
            AnswerValidator.answersMatch("25%", "0.25"));
        expect("answersMatch is false for different values",
            !AnswerValidator.answersMatch("39", "41"));
        expect("answersMatch is false for a sign flip",
            !AnswerValidator.answersMatch("2/7", "-7/2"));
        expect("answersMatch ignores a trailing unit on one side",
            AnswerValidator.answersMatch("21 square meters", "21"));
        expect("answersMatch ignores a trailing unit written as cm",
            AnswerValidator.answersMatch("8 cm", "8"));
        expect("answersMatch keeps units honest when both sides carry them",
            !AnswerValidator.answersMatch("8 cm", "9 cm"));
        expect("answersMatch does not treat 2x as the number 2",
            !AnswerValidator.answersMatch("2x", "2"));
        expect("answersMatch is false when either side is null",
            !AnswerValidator.answersMatch(null, "39"));

        // ---- assembleChoices: the verified answer must survive into the choice list ----
        try {
            JSONArray built = AnswerValidator.assembleChoices("10000",
                List.of("11000", "13000", "8500"));
            expect("assembleChoices produces 4 choices", built.length() == 4);
            String correctId = AnswerValidator.idOfChoiceText(built, "10000");
            expect("assembleChoices keeps the verified answer findable",
                List.of("A", "B", "C", "D").contains(correctId));
        } catch (GenerationException e) {
            System.out.println("FAIL: assembleChoices threw: " + e.getMessage());
            allOk = false;
        }
        try {
            AnswerValidator.assembleChoices("10000", List.of("11000", "10,000", "8500"));
            System.out.println("FAIL: assembleChoices should reject a distractor equal to the answer");
            allOk = false;
        } catch (GenerationException e) {
            System.out.println("PASS: assembleChoices rejects a distractor equal to the answer");
        }
        try {
            AnswerValidator.assembleChoices("10000", List.of("11000", "8500"));
            System.out.println("FAIL: assembleChoices should reject fewer than 3 distractors");
            allOk = false;
        } catch (GenerationException e) {
            System.out.println("PASS: assembleChoices rejects fewer than 3 distractors");
        }

        // ---- answer comparison must survive the forms a solver actually replies in ----
        // Each of these was observed rejecting a perfectly good generated question,
        // burning all three attempts on a disagreement that was only a spelling
        // difference between the writer and the solver.
        expect("6sqrt(3) matches the Unicode radical form",
            AnswerValidator.answersMatch("6sqrt(3)", "6\u221a3"));
        expect("sqrt(pi) matches the Greek-letter form",
            AnswerValidator.answersMatch("6sqrt(pi)", "6\u221a\u03c0"));
        expect("a function-name prefix does not block a match",
            AnswerValidator.answersMatch("f(n)=17n+350", "17n+350"));
        expect("the Unicode minus sign matches a hyphen",
            AnswerValidator.answersMatch("\u22125", "-5"));
        expect("a real numeric disagreement is still caught",
            !AnswerValidator.answersMatch("9.5", "9.4"));
        expect("a bare number does not match an algebraic term",
            !AnswerValidator.answersMatch("2x", "2"));
        expect("different radicands still disagree",
            !AnswerValidator.answersMatch("sqrt(3)", "sqrt(2)"));

        // ---- markup detection must not fire on ordinary inequalities ----
        expect("an inequality range is not markup",
            !AnswerValidator.containsMarkup("the domain is -3 < x, y > 0"));
        expect("a chained comparison is not markup",
            !AnswerValidator.containsMarkup("if a < b and b > c then a < c"));
        expect("an HTML paragraph is markup",
            AnswerValidator.containsMarkup("<p>hi</p>"));
        expect("MathML is markup",
            AnswerValidator.containsMarkup("<math alttext=\"x\"><mi>x</mi></math>"));

        // ---- a walkthrough may not disown the question it belongs to ----
        expect("an explanation that says the stem does not work is rejected",
            AnswerValidator.selfContradiction(
                "We want the x-coefficient to be 21, so 2p + 3n = 21. Checking integer factor "
                + "pairs of -16 shows that no pair satisfies this, so c = 21 does not work "
                + "with the required integer factorization.") != null);
        expect("an ordinary walkthrough is not flagged",
            AnswerValidator.selfContradiction(
                "Substituting x = 3 gives 12, so the answer is 12.") == null);
        expect("ruling out a distractor is not flagged",
            AnswerValidator.selfContradiction(
                "Choice B does not satisfy the second equation, so it is wrong.") == null);

        // ---- the solver's unsolvable escape hatch ----
        expect("INCONSISTENT is recognized",
            AnswerValidator.isInconsistent("INCONSISTENT"));
        expect("an ordinary answer is not INCONSISTENT",
            !AnswerValidator.isInconsistent("12"));
        expectParse("INCONSISTENT survives choice matching",
            "reasoning\nFINAL: INCONSISTENT",
            new JSONArray().put(new JSONObject().put("id", "A").put("text", "1"))
                           .put(new JSONObject().put("id", "B").put("text", "2")),
            AnswerValidator.INCONSISTENT);

        // ---- seeds whose question only means something with its choices ----
        expect("a \"which of the following\" seed is choice-dependent",
            AnswerValidator.isChoiceDependent(
                seedWithStem("Which of the following must be an integer?")));
        expect("a plain value question is not choice-dependent",
            !AnswerValidator.isChoiceDependent(
                seedWithStem("What is the value of n + p?")));

        if (allOk) {
            System.out.println("VALIDATE_OK");
        } else {
            System.exit(1);
        }
    }

    private static void expectParseOpen(String label, String reply, String expected) {
        try {
            String result = AnswerValidator.parseOpenSolverAnswer(reply);
            if (expected.equals(result)) {
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

    private static satapp.model.Question seedWithStem(String stem) {
        satapp.model.Question seed = new satapp.model.Question();
        seed.setSection("Math");
        seed.setStem(stem);
        return seed;
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
