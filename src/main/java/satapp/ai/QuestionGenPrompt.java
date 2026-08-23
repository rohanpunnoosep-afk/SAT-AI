package satapp.ai;

import org.json.JSONArray;
import satapp.model.Question;

public class QuestionGenPrompt {

    public static String build(Question seed, boolean stricterRetry) {
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
        sb.append("Write a NEW multiple-choice question that tests the exact same skill at the ")
          .append("same difficulty level. Do not reword or lightly rephrase the seed question — ")
          .append("produce a genuinely different question testing the same underlying concept.\n\n");
        sb.append("Solve your own question completely in the \"work\" field BEFORE choosing ")
          .append("\"correct_answer\". \"correct_answer\" must be the id of the choice whose text ")
          .append("equals the result of your work. Every choice text must be distinct, and the ")
          .append("distractors must be wrong.\n\n");
        sb.append("Reply with strict JSON only, no markdown code fence, no prose before or after, ")
          .append("in exactly this shape:\n");
        sb.append("{\"stem\": \"...\", \"choices\": [{\"id\":\"A\",\"text\":\"...\"}, ")
          .append("{\"id\":\"B\",\"text\":\"...\"}, ...], \"work\": \"...\", \"correct_answer\": \"B\", ")
          .append("\"explanation\": \"...\"}\n");

        if (stricterRetry) {
            sb.append("\nIMPORTANT: your previous reply was invalid. The entire reply MUST be a ")
              .append("single JSON object with exactly these keys: \"stem\" (non-blank string), ")
              .append("\"choices\" (array of at least 2 objects each with non-blank \"id\" and ")
              .append("\"text\", with unique ids), \"work\" (non-blank string showing your full ")
              .append("solution before you commit to an answer), \"correct_answer\" (must match one ")
              .append("choice id, case-insensitively), and \"explanation\" (non-blank string). Do not ")
              .append("include any markdown fence or any text outside the JSON object.\n");
        }

        return sb.toString();
    }
}
