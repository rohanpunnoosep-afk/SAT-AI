package satapp.ai;

import org.json.JSONArray;
import satapp.model.Question;

public class QuestionGenPrompt {

    public static final int LEVEL_SAME_PROCEDURE = 1;
    public static final int LEVEL_SAME_TECHNIQUE = 2;

    public static String build(Question seed, boolean stricterRetry) {
        return build(seed, LEVEL_SAME_PROCEDURE, stricterRetry);
    }

    public static String build(Question seed, int level, boolean stricterRetry) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert SAT question writer.\n");
        sb.append("Here is a seed question:\n");
        sb.append("Domain: ").append(seed.getDomain()).append("\n");
        sb.append("Skill: ").append(seed.getSkill()).append("\n");
        sb.append("Difficulty: ").append(seed.getDifficulty()).append("\n");
        sb.append("Stem: ").append(seed.getStem()).append("\n");
        if (seed.getChoicesJson() != null) {
            JSONArray choices = new JSONArray(seed.getChoicesJson());
            sb.append("Choices: ").append(choices.toString()).append("\n");
        }
        sb.append("Correct answer: ").append(seed.getCorrectAnswer()).append("\n\n");

        if (level == LEVEL_SAME_TECHNIQUE) {
            sb.append("Write a NEW multiple-choice question that applies the same broad technique ")
              .append("or concept as the seed question, but asks something genuinely different about ")
              .append("it — it must NOT be solvable by following the exact same step-by-step ")
              .append("procedure as the seed question. Change what is being asked (e.g. ask for a ")
              .append("different derived quantity, a different form of the relationship, or an ")
              .append("equation instead of a specific value) so the student has to apply the ")
              .append("underlying idea in a new way rather than repeat the same steps. Keep the same ")
              .append("difficulty level and the same domain/skill.\n\n");
        } else {
            sb.append("Write a NEW multiple-choice question that tests the exact same skill using the ")
              .append("exact same solution procedure, at the same difficulty level, just with ")
              .append("different numbers, symbols, or surface expression. Do not reword or lightly ")
              .append("rephrase the seed question — produce a genuinely different question that a ")
              .append("student would solve with the identical sequence of steps.\n\n");
        }

        sb.append("Reply with strict JSON only, no markdown code fence, no prose before or after, ")
          .append("in exactly this shape:\n");
        sb.append("{\"stem\": \"...\", \"choices\": [{\"id\":\"A\",\"text\":\"...\"}, ")
          .append("{\"id\":\"B\",\"text\":\"...\"}, ...], \"correct_answer\": \"B\", \"explanation\": \"...\"}\n");

        if (stricterRetry) {
            sb.append("\nIMPORTANT: your previous reply was invalid. The entire reply MUST be a ")
              .append("single JSON object with exactly these keys: \"stem\" (non-blank string), ")
              .append("\"choices\" (array of at least 2 objects each with non-blank \"id\" and ")
              .append("\"text\", with unique ids), \"correct_answer\" (must match one choice id, ")
              .append("case-insensitively), and \"explanation\" (non-blank string). Do not include ")
              .append("any markdown fence or any text outside the JSON object.\n");
        }

        return sb.toString();
    }
}
