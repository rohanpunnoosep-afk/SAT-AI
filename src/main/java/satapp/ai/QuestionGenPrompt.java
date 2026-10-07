package satapp.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import satapp.model.Question;

public class QuestionGenPrompt {

    public static final int LEVEL_SAME_PROCEDURE = 1;
    public static final int LEVEL_SAME_TECHNIQUE = 2;

    // Shared house rules. The generator used to copy the seed's College Board markup
    // into its own choices, which broke the validator and confused the blind solver, so
    // every prompt now states the plain-text contract explicitly.
    private static final String PLAIN_TEXT_RULE =
        "Write everything in plain text. Do NOT use HTML, MathML, LaTeX, or markdown of "
        + "any kind -- no tags, no backslashes, no dollar signs. Write math the way it "
        + "would be typed: x^2, sqrt(5), 3/4, (2x + 1)/(x - 3).\n";

    public static String build(Question seed, String priorFailure) {
        return build(seed, LEVEL_SAME_PROCEDURE, priorFailure);
    }

    /**
     * Tells the model what the last attempt got wrong. Retries used to send one fixed
     * "a solver disagreed with you" paragraph no matter why the attempt was rejected, so
     * a question thrown out for being ambiguous was retried with advice about arithmetic
     * and failed the same way three times over. Quoting the real rejection is what lets
     * the second attempt fix the actual defect.
     */
    private static void appendRetryHint(StringBuilder sb, String priorFailure, String shape) {
        if (priorFailure == null || priorFailure.trim().isEmpty()) {
            return;
        }
        sb.append("\nIMPORTANT -- your previous attempt was REJECTED for this reason:\n");
        sb.append("  ").append(priorFailure.trim()).append("\n");
        sb.append("Write a different question that cannot fail that way again. In particular:\n");
        sb.append("- Before you commit to the wording, check that the conditions you state are ")
          .append("actually satisfiable: pick the answer and the numbers FIRST, then write the ")
          .append("stem around them. Never state a constraint you have not verified has a ")
          .append("solution.\n");
        sb.append("- Make sure exactly one answer fits the wording. If a different but equally ")
          .append("valid answer could be given, the question is ambiguous -- tighten it.\n");
        sb.append("- Do the arithmetic twice and keep the numbers clean.\n");
        sb.append("- If you find yourself writing that some value \"does not work\" or that ")
          .append("\"no such value exists\", the question is broken. Start over with different ")
          .append("numbers rather than explaining around it.\n");
        sb.append(shape);
    }

    /** Shared seed description, flattened out of College Board markup. */
    private static void appendSeed(StringBuilder sb, Question seed, boolean includeChoices) {
        sb.append("Here is a seed question:\n");
        sb.append("Domain: ").append(seed.getDomain()).append("\n");
        sb.append("Skill: ").append(seed.getSkill()).append("\n");
        sb.append("Difficulty: ").append(seed.getDifficulty()).append("\n");
        if (seed.getStimulus() != null && !seed.getStimulus().trim().isEmpty()) {
            sb.append("Passage: ").append(AnswerValidator.plainText(seed.getStimulus())).append("\n");
        }
        sb.append("Stem: ").append(AnswerValidator.plainText(seed.getStem())).append("\n");
        if (includeChoices && seed.getChoicesJson() != null) {
            JSONArray choices = new JSONArray(seed.getChoicesJson());
            for (int i = 0; i < choices.length(); i++) {
                JSONObject choice = choices.getJSONObject(i);
                sb.append("  ").append(choice.optString("id", ""))
                  .append(". ").append(AnswerValidator.plainText(choice.optString("text", "")))
                  .append("\n");
            }
        }
        sb.append("Correct answer: ").append(AnswerValidator.plainText(seed.getCorrectAnswer()))
          .append("\n\n");
    }

    private static void appendLevelInstruction(StringBuilder sb, int level, String noun) {
        if (level == LEVEL_SAME_TECHNIQUE) {
            sb.append("Write a NEW ").append(noun).append(" that applies the same broad technique ")
              .append("or concept as the seed question, but asks something genuinely different about ")
              .append("it -- it must NOT be solvable by following the exact same step-by-step ")
              .append("procedure as the seed question. Change what is being asked (e.g. ask for a ")
              .append("different derived quantity, a different form of the relationship, or an ")
              .append("equation instead of a specific value) so the student has to apply the ")
              .append("underlying idea in a new way rather than repeat the same steps. Keep the same ")
              .append("difficulty level and the same domain/skill.\n\n");
        } else {
            sb.append("Write a NEW ").append(noun).append(" that tests the exact same skill using the ")
              .append("exact same solution procedure, at the same difficulty level, just with ")
              .append("different numbers, symbols, or surface expression. Do not reword or lightly ")
              .append("rephrase the seed question -- produce a genuinely different question that a ")
              .append("student would solve with the identical sequence of steps.\n\n");
        }
    }

    /**
     * Answer-first prompt used for math. The model writes the question WITHOUT choices,
     * so there is nothing for it to pattern-match onto: the answer has to fall out of its
     * own work, and an independent solver can then be asked the same open question.
     */
    public static String buildOpenEnded(Question seed, int level, String priorFailure) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert SAT question writer.\n");
        appendSeed(sb, seed, true);
        appendLevelInstruction(sb, level, "question");

        sb.append("Write the question as an OPEN-ENDED question with NO answer choices. It must ")
          .append("have exactly one correct answer that is a single number or a single expression, ")
          .append("and the wording must make that answer unambiguous (state units, rounding, and ")
          .append("the exact form wanted if there is any doubt).\n\n");
        sb.append("Build the question in this order, and put the whole construction in ")
          .append("\"work\" BEFORE you write \"stem\" or \"answer\":\n");
        sb.append("1. Choose the underlying numbers first and check they satisfy every ")
          .append("condition you are about to state. If you plan to fix a coefficient, a ")
          .append("total, or a constant, verify a solution actually exists for that value ")
          .append("before you put it in the stem.\n");
        sb.append("2. Solve the question you just built, all the way through.\n");
        sb.append("3. Re-read \"work\" line by line and confirm \"answer\" is what it produces.\n\n");
        sb.append("The question you write MUST be satisfiable exactly as worded. Never state a ")
          .append("condition that nothing satisfies. If your own work shows that a value you ")
          .append("put in the stem \"does not work\", that no such integer or pair exists, or ")
          .append("that the constraints conflict, then the question is broken: go back and ")
          .append("change the stem so it is consistent. Do NOT write the broken stem and then ")
          .append("solve a corrected version of it -- a walkthrough that says the question does ")
          .append("not work is worse than a wrong answer.\n\n");
        sb.append("The \"explanation\" must be the student-facing walkthrough. It must follow ")
          .append("only from what the stem actually says and must end at the same answer.\n\n");
        sb.append(PLAIN_TEXT_RULE).append("\n");
        sb.append("Reply with strict JSON only, no markdown code fence, no prose before or after, ")
          .append("in exactly this shape:\n");
        sb.append("{\"stem\": \"...\", \"work\": \"...\", \"answer\": \"...\", \"explanation\": \"...\"}\n");

        appendRetryHint(sb, priorFailure,
            "- The entire reply MUST be a single JSON object with exactly the keys \"stem\", "
            + "\"work\", \"answer\", and \"explanation\", all non-blank strings, with no "
            + "markdown fence and no text outside the JSON object.\n");
        return sb.toString();
    }

    /**
     * Second call of the answer-first pipeline: the answer is already verified, so this
     * only has to invent wrong-but-tempting choices around it.
     */
    public static String buildDistractors(String stem, String answer) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert SAT question writer building the answer choices for a ")
          .append("multiple-choice question whose correct answer is already fixed.\n\n");
        sb.append("Question: ").append(stem).append("\n");
        sb.append("Correct answer: ").append(answer).append("\n\n");
        sb.append("Write exactly 3 INCORRECT answer choices. Each one must be:\n");
        sb.append("- genuinely wrong (re-solve the question and confirm none of them is correct),\n");
        sb.append("- tempting, i.e. the result of a realistic student mistake such as a sign error, ")
          .append("using the wrong operation, or stopping one step early,\n");
        sb.append("- distinct from the correct answer and from each other,\n");
        sb.append("- in the same format and units as the correct answer.\n\n");
        sb.append(PLAIN_TEXT_RULE).append("\n");
        sb.append("Reply with strict JSON only, no markdown code fence, no prose before or after, ")
          .append("in exactly this shape:\n");
        sb.append("{\"distractors\": [\"...\", \"...\", \"...\"]}\n");
        return sb.toString();
    }

    /**
     * Choices-first prompt, still used for Reading and Writing, where a question has no
     * meaning without its choices and so cannot be posed open-ended.
     */
    public static String build(Question seed, int level, String priorFailure) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert SAT question writer.\n");
        appendSeed(sb, seed, true);
        appendLevelInstruction(sb, level, "multiple-choice question");

        sb.append("The question must be answerable exactly as worded -- never state a ")
          .append("condition that nothing satisfies, and never write an explanation that says ")
          .append("the question does not work. If your work shows the stem is broken, change ")
          .append("the stem.\n\n");
        sb.append("Decide the correct answer in the \"work\" field BEFORE choosing ")
          .append("\"correct_answer\": state what the question is asking for, then say why each ")
          .append("choice is right or wrong. \"correct_answer\" must be the id of the choice your ")
          .append("work supports. Exactly one choice may be defensible -- every distractor must be ")
          .append("clearly wrong, and no two choices may say the same thing.\n\n");
        // "work" is the model's scratchpad and is never shown; "explanation" is the only
        // thing the student reads, so it has to stand on its own rather than assert the
        // conclusion in one line.
        sb.append("\"explanation\" is what the student sees after answering, and it is the ")
          .append("teaching, not a verdict. Walk through the full solution the way a tutor ")
          .append("would: say what the question is really testing, show each step and the ")
          .append("reasoning that motivates it, and name the correct choice at the end. Where a ")
          .append("distractor reflects a specific likely mistake, say what that mistake is. Do ")
          .append("not compress it to a single sentence and do not just restate the answer.\n\n");
        sb.append(PLAIN_TEXT_RULE).append("\n");
        sb.append("Reply with strict JSON only, no markdown code fence, no prose before or after, ")
          .append("in exactly this shape:\n");
        sb.append("{\"stem\": \"...\", \"choices\": [{\"id\":\"A\",\"text\":\"...\"}, ")
          .append("{\"id\":\"B\",\"text\":\"...\"}, ...], \"work\": \"...\", \"correct_answer\": \"B\", ")
          .append("\"explanation\": \"...\"}\n");

        appendRetryHint(sb, priorFailure,
            "- The entire reply MUST be a single JSON object with exactly these keys: \"stem\" "
            + "(non-blank string), \"choices\" (array of at least 2 objects each with non-blank "
            + "\"id\" and \"text\", with unique ids), \"work\" (non-blank string showing your "
            + "full solution before you commit to an answer), \"correct_answer\" (must match one "
            + "choice id, case-insensitively), and \"explanation\" (non-blank string that names "
            + "the correct choice). No markdown fence, no markup, no text outside the JSON "
            + "object.\n");
        return sb.toString();
    }
}
