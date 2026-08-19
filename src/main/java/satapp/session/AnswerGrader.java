package satapp.session;

import satapp.model.Question;

public class AnswerGrader {

    private static final double TOLERANCE = 1e-4;

    public static boolean isCorrect(Question q, String submitted) {
        if (submitted == null) {
            return false;
        }
        String submittedTrimmed = submitted.trim();
        String stored = q.getCorrectAnswer();
        if (stored == null) {
            return false;
        }

        if ("mcq".equalsIgnoreCase(q.getQuestionType())) {
            return submittedTrimmed.equalsIgnoreCase(stored.trim());
        }

        // spr: comma-separated list of accepted spellings
        String[] accepted = stored.split(",");
        for (String rawAccept : accepted) {
            String accept = rawAccept.trim();
            if (submittedTrimmed.equalsIgnoreCase(accept)) {
                return true;
            }
            Double submittedNum = parseNumeric(submittedTrimmed);
            Double acceptNum = parseNumeric(accept);
            if (submittedNum != null && acceptNum != null) {
                double tolerance = TOLERANCE * Math.max(1.0, Math.abs(acceptNum));
                if (Math.abs(submittedNum - acceptNum) <= tolerance) {
                    return true;
                }
            }
        }
        return false;
    }

    static Double parseNumeric(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.contains("/")) {
            String[] parts = v.split("/", 2);
            if (parts.length != 2) {
                return null;
            }
            try {
                double numerator = Double.parseDouble(parts[0].trim());
                double denominator = Double.parseDouble(parts[1].trim());
                if (denominator == 0) {
                    return null;
                }
                return numerator / denominator;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
