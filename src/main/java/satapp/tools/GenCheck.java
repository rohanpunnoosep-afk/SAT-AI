package satapp.tools;

import org.json.JSONObject;
import satapp.ai.GenerationException;
import satapp.ai.OpenAiClient;

public class GenCheck {

    private static boolean allOk = true;

    public static void main(String[] args) {
        String wellFormed = "{\"stem\":\"What is 2+2?\",\"choices\":[" +
            "{\"id\":\"A\",\"text\":\"3\"},{\"id\":\"B\",\"text\":\"4\"},{\"id\":\"C\",\"text\":\"5\"}]," +
            "\"correct_answer\":\"B\",\"explanation\":\"2+2=4\"}";

        expectAccept("well-formed reply parses with correct_answer B", wellFormed, "B");

        String fenced = "```json\n" + wellFormed + "\n```";
        expectAccept("fenced reply also parses", fenced, "B");

        expectReject("not json at all is rejected", "not json at all");
        expectReject("array instead of object is rejected", "[]");

        String blankStem = "{\"stem\":\"  \",\"choices\":[" +
            "{\"id\":\"A\",\"text\":\"3\"},{\"id\":\"B\",\"text\":\"4\"}]," +
            "\"correct_answer\":\"B\",\"explanation\":\"x\"}";
        expectReject("blank stem is rejected", blankStem);

        String singleChoice = "{\"stem\":\"q\",\"choices\":[{\"id\":\"A\",\"text\":\"3\"}]," +
            "\"correct_answer\":\"A\",\"explanation\":\"x\"}";
        expectReject("single choice is rejected", singleChoice);

        String dupChoiceIds = "{\"stem\":\"q\",\"choices\":[" +
            "{\"id\":\"A\",\"text\":\"3\"},{\"id\":\"A\",\"text\":\"4\"}]," +
            "\"correct_answer\":\"A\",\"explanation\":\"x\"}";
        expectReject("duplicate choice ids are rejected", dupChoiceIds);

        String badCorrectAnswer = "{\"stem\":\"q\",\"choices\":[" +
            "{\"id\":\"A\",\"text\":\"3\"},{\"id\":\"B\",\"text\":\"4\"}]," +
            "\"correct_answer\":\"Z\",\"explanation\":\"x\"}";
        expectReject("correct_answer matching no choice id is rejected", badCorrectAnswer);

        String missingExplanation = "{\"stem\":\"q\",\"choices\":[" +
            "{\"id\":\"A\",\"text\":\"3\"},{\"id\":\"B\",\"text\":\"4\"}]," +
            "\"correct_answer\":\"B\"}";
        expectReject("missing explanation is rejected", missingExplanation);

        String realisticEnvelope = new JSONObject()
            .put("choices", new org.json.JSONArray()
                .put(new JSONObject().put("message", new JSONObject()
                    .put("role", "assistant").put("content", wellFormed))))
            .toString();
        try {
            String content = OpenAiClient.extractMessageContent(realisticEnvelope);
            if (content.equals(wellFormed)) {
                System.out.println("PASS: extractMessageContent pulls content from realistic envelope");
            } else {
                System.out.println("FAIL: extractMessageContent returned unexpected content: " + content);
                allOk = false;
            }
        } catch (GenerationException e) {
            System.out.println("FAIL: extractMessageContent threw on realistic envelope: " + e.getMessage());
            allOk = false;
        }

        try {
            OpenAiClient.extractMessageContent("{}");
            System.out.println("FAIL: extractMessageContent should reject {}");
            allOk = false;
        } catch (GenerationException e) {
            System.out.println("PASS: extractMessageContent rejects {}");
        }

        if (allOk) {
            System.out.println("GEN_PARSE_OK");
        } else {
            System.exit(1);
        }
    }

    private static void expectAccept(String label, String raw, String expectedCorrectAnswer) {
        try {
            JSONObject result = OpenAiClient.parseGeneratedQuestion(raw);
            if (expectedCorrectAnswer.equals(result.getString("correct_answer"))) {
                System.out.println("PASS: " + label);
            } else {
                System.out.println("FAIL: " + label + " (wrong correct_answer: " + result.getString("correct_answer") + ")");
                allOk = false;
            }
        } catch (GenerationException e) {
            System.out.println("FAIL: " + label + " (threw: " + e.getMessage() + ")");
            allOk = false;
        }
    }

    private static void expectReject(String label, String raw) {
        try {
            OpenAiClient.parseGeneratedQuestion(raw);
            System.out.println("FAIL: " + label + " (did not throw)");
            allOk = false;
        } catch (GenerationException e) {
            System.out.println("PASS: " + label);
        }
    }
}
