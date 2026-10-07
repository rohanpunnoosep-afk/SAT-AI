package satapp.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import satapp.model.Question;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnswerValidator {

    /**
     * What a blind solver reports when the question as written cannot be solved -- the
     * constraints contradict each other, or no value satisfies them. Without this escape
     * hatch a solver handed an impossible stem quietly "repairs" it to the nearest
     * sensible question and answers that instead; when both solvers repair it the same
     * way they agree, and a broken question passes validation looking verified.
     */
    public static final String INCONSISTENT = "INCONSISTENT";

    /**
     * Phrases a walkthrough only reaches for once it has noticed the stem is unsatisfiable
     * and has started quietly solving a different question -- the defect behind an
     * explanation that reads "... so c = 21 does not work with the required integer
     * factorization" under a stem that states c = 21.
     *
     * <p>Deliberately narrow. Wording that is normal when ruling a distractor out ("choice
     * B does not satisfy the equation") is excluded, and the check only runs on the
     * answer-first walkthrough, which has no distractors to discuss.
     */
    private static final Pattern SELF_CONTRADICTION = Pattern.compile(
        "(?i)(does ?n[o']t work|cannot work|can't work"
        + "|no (?:such )?(?:pair|value|integer|constant)s? (?:satisf|exist|work)"
        + "|no such (?:pair|value|integer|constant|factorization)"
        + "|is impossible|no valid (?:factorization|pair|value))");

    private static final Pattern FINAL_LINE = Pattern.compile(
        "(?i)FINAL\\s*:\\s*([A-Za-z0-9]+)");

    // The open-ended solver answers with a value rather than a choice letter, so the
    // capture has to run to the end of the line instead of stopping at the first token.
    private static final Pattern FINAL_VALUE_LINE = Pattern.compile(
        "(?im)^\\s*\\**\\s*FINAL\\s*:\\s*(.+?)\\s*$");

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    /**
     * A real HTML/MathML tag, as opposed to any pair of angle brackets. The blanket
     * "<[^>]+>" test rejected ordinary math -- a domain written "-3 < x, y > 0" or a
     * chained inequality reads as a tag to it -- and because the writer keeps producing
     * the same natural phrasing, that rejection repeated until the retry budget ran out.
     * Detection now requires something that actually looks like a tag name.
     */
    private static final Pattern HTML_TAG = Pattern.compile(
        "(?i)</?(?:p|br|div|span|math|mrow|mi|mn|mo|msup|msub|mfrac|mfenced|msqrt|mroot"
        + "|mtable|mtr|mtd|mstyle|mtext|sup|sub|b|i|em|strong|ul|ol|li|table|tr|td|th"
        + "|a|img|h[1-6])\\b[^>]*>");

    // Unicode the solver reaches for even when told to answer in plain text. The writer
    // types "6sqrt(3)" and a solver answers "6\u221a3"; those are the same answer, and
    // treating them as a disagreement threw away perfectly good questions.
    private static final Pattern SQRT_PARENS = Pattern.compile("sqrt\\(([^()]*)\\)");
    private static final Pattern FUNCTION_PREFIX = Pattern.compile(
        "^[a-z]{1,3}\\([a-z]\\)=");
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile(
        "(-?[0-9]+(?:[.][0-9]+)?(?:/[0-9]+(?:[.][0-9]+)?)?)([a-z]{2,}(?:[a-z]*)?)");
    private static final Pattern MATH_WITH_ALTTEXT = Pattern.compile(
        "(?is)<math[^>]*\\balttext=\"([^\"]*)\"[^>]*>.*?</math>");

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
                String normalized = plainText(choice.optString("text", ""));
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
                referencesId = idPattern.matcher(plainText(explanation)).find();
            }
            String flatExplanation = plainText(explanation).toLowerCase();
            String flatCorrect = correctText == null ? "" : plainText(correctText).toLowerCase();
            boolean referencesText = !flatCorrect.isEmpty() && flatExplanation.contains(flatCorrect);
            if (!referencesId && !referencesText) {
                problems.add("explanation does not reference the marked correct choice");
            }
        }

        // The model occasionally copies the prompt's own seed header into the stem
        // ("Domain: ... Skill: ... Difficulty: ... Passage: ..."), which is scaffolding
        // no student should ever see.
        String flatStem = plainText(generated.optString("stem", ""));
        if (flatStem.matches("(?is).*\\bDomain\\s*:.*\\bSkill\\s*:.*\\bDifficulty\\s*:.*")) {
            problems.add("stem repeats the prompt's seed header");
        }

        if (containsMarkup(generated.optString("stem", ""))) {
            problems.add("stem contains HTML or MathML markup");
        }
        if (choices != null) {
            for (int i = 0; i < choices.length(); i++) {
                if (containsMarkup(choices.getJSONObject(i).optString("text", ""))) {
                    problems.add("choice contains HTML or MathML markup");
                    break;
                }
            }
        }

        if (seed != null && seed.getStem() != null) {
            String generatedStem = plainText(generated.optString("stem", ""));
            String seedStem = plainText(seed.getStem());
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
        sb.append("FINAL: <choice id>\n\n");
        sb.append("If the question as written contradicts itself, or if no choice is ")
          .append("defensible, or if more than one choice is equally defensible, do not ")
          .append("guess the intended one -- reply with exactly:\n");
        sb.append("FINAL: ").append(INCONSISTENT).append("\n");
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
            if (lastToken.equalsIgnoreCase(INCONSISTENT)) {
                return INCONSISTENT;
            }
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


    /**
     * Flattens College Board markup (HTML plus MathML) down to something a language
     * model reads cleanly. Seed stems arrive wrapped in <p>/<math> markup; feeding that
     * markup into a prompt is what taught the generator to emit markup of its own, which
     * in turn broke both the explanation check and the blind solver.
     */
    public static String plainText(String markup) {
        if (markup == null) {
            return "";
        }
        // A <math> element with alttext carries an unambiguous spoken form of the
        // expression; prefer it over the flattened tag soup.
        String text = MATH_WITH_ALTTEXT.matcher(markup).replaceAll(
            Matcher.quoteReplacement(" ") + "$1" + Matcher.quoteReplacement(" "));
        text = TAG.matcher(text).replaceAll(" ");
        text = text.replace("&nbsp;", " ")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'");
        return normalizeWhitespace(text);
    }

    public static boolean isMathSection(Question seed) {
        return seed != null && seed.getSection() != null
            && seed.getSection().toLowerCase().contains("math");
    }

    /**
     * True when the seed's question only means something alongside its choices -- "Which
     * of the following must be an integer?" and friends. These cannot be posed
     * open-ended: stripped of the choice list they have many equally correct answers, so
     * two blind solvers name two different valid ones, read as disagreement, and every
     * retry is rejected as "ambiguous". Such seeds go through the choices-first pipeline
     * instead, where the solver picks from the same fixed list the writer used.
     */
    public static boolean isChoiceDependent(Question seed) {
        if (seed == null || seed.getStem() == null) {
            return false;
        }
        String stem = plainText(seed.getStem()).toLowerCase();
        return stem.contains("which of the following")
            || stem.contains("which of these")
            || stem.contains("which expression")
            || stem.contains("which equation")
            || stem.contains("which statement")
            || stem.contains("which choice")
            || stem.contains("could be the")
            || stem.contains("must be");
    }

    /**
     * Guards the failure the answer-first pipeline cannot otherwise see: the writer
     * invents a stem no value satisfies, notices halfway through, and silently solves a
     * repaired version instead. The final answer can even be self-consistent while the
     * walkthrough the student actually reads still says the question does not work.
     *
     * @return the offending phrase, or null when the walkthrough is clean.
     */
    public static String selfContradiction(String explanation) {
        Matcher matcher = SELF_CONTRADICTION.matcher(plainText(explanation));
        return matcher.find() ? matcher.group() : null;
    }

    /** True when a solver reported the question itself as unsolvable as written. */
    public static boolean isInconsistent(String answer) {
        return answer != null && answer.trim().equalsIgnoreCase(INCONSISTENT);
    }

    /** Blind-solve prompt for an open-ended question: no choices to anchor the solver. */
    public static String buildOpenSolverPrompt(String stem) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert SAT test taker.\n");
        sb.append("Solve the question below from scratch. Show your reasoning step by step ")
          .append("FIRST, then state your final answer.\n\n");
        sb.append("Question: ").append(stem).append("\n");
        sb.append("\nYour reply MUST end with a final line in exactly this form, ")
          .append("and nothing after it:\n");
        sb.append("FINAL: <your answer>\n");
        sb.append("Give the answer alone on that line -- a number, expression, or equation ")
          .append("in plain text, with no units, no words, and no explanation.\n");
        sb.append("Put it in ONE canonical form so it can be compared exactly: fully ")
          .append("simplified and expanded, no function name or \"y =\" in front of it, ")
          .append("ASCII only (write sqrt(3), not a radical sign, and pi, not a Greek ")
          .append("letter), and a fraction in lowest terms rather than a rounded decimal ")
          .append("unless the question asked you to round.\n\n");
        sb.append("Do NOT repair the question. If the conditions as written contradict each ")
          .append("other, if no value satisfies them, or if more than one different answer ")
          .append("fits the wording, do not answer the nearest sensible question instead -- ")
          .append("reply with exactly:\n");
        sb.append("FINAL: ").append(INCONSISTENT).append("\n");
        return sb.toString();
    }

    /** Reads the value off the last FINAL line of an open-ended solver reply. */
    public static String parseOpenSolverAnswer(String reply) throws GenerationException {
        if (reply != null) {
            String last = null;
            Matcher matcher = FINAL_VALUE_LINE.matcher(reply);
            while (matcher.find()) {
                last = matcher.group(1);
            }
            if (last != null) {
                String cleaned = stripAnswerDecoration(last);
                if (!cleaned.isEmpty()) {
                    return cleaned;
                }
            }
        }
        throw new GenerationException("solver reply did not contain a usable FINAL answer");
    }

    private static String stripAnswerDecoration(String raw) {
        String value = raw.trim();
        value = value.replaceAll("^\\**\\s*", "").replaceAll("\\s*\\**$", "");
        value = value.replaceAll("^\\$+\\s*", "").replaceAll("\\s*\\$+$", "");
        value = value.replaceAll("\\s*[.]$", "");
        value = value.replaceAll("^`+|`+$", "");
        return value.trim();
    }

    /**
     * Compares two free-form answers the way the grader will: exact text after
     * normalization, or numerically equal within a relative tolerance.
     */
    public static boolean answersMatch(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String na = normalizeAnswerText(a);
        String nb = normalizeAnswerText(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        if (na.equals(nb)) {
            return true;
        }
        Double numA = parseNumeric(na);
        Double numB = parseNumeric(nb);
        if (numA != null && numB != null) {
            return numbersMatch(numA, numB);
        }

        // The solver is told to answer with a bare value while the question writer
        // usually keeps the units ("21" vs "21 square meters"). Those are the same
        // answer, so strip a trailing unit before giving up -- but only when one side
        // is a bare number, so that "2x" is never treated as "2".
        String unitA = trailingUnit(na);
        String unitB = trailingUnit(nb);
        if (unitA != null || unitB != null) {
            Double baseA = numA != null ? numA : parseNumeric(stripTrailingUnit(na, unitA));
            Double baseB = numB != null ? numB : parseNumeric(stripTrailingUnit(nb, unitB));
            boolean unitsCompatible = unitA == null || unitB == null || unitA.equals(unitB);
            if (baseA != null && baseB != null && unitsCompatible) {
                return numbersMatch(baseA, baseB);
            }
        }
        return false;
    }

    private static boolean numbersMatch(double a, double b) {
        return Math.abs(a - b) <= 1e-6 * Math.max(1.0, Math.abs(b));
    }

    /**
     * Returns the trailing unit word of a value like "21squaremeters", or null when the
     * value is not a number followed by a multi-letter unit. Single-letter tails are
     * rejected so that algebraic terms such as "2x" keep their meaning.
     */
    private static String trailingUnit(String normalized) {
        Matcher matcher = NUMBER_WITH_UNIT.matcher(normalized);
        return matcher.matches() ? matcher.group(2) : null;
    }

    private static String stripTrailingUnit(String normalized, String unit) {
        return unit == null ? normalized
            : normalized.substring(0, normalized.length() - unit.length());
    }

    static String normalizeAnswerText(String value) {
        String v = plainText(value).toLowerCase();
        v = v.replace("$", "").replace(",", "").replace("&", "");

        // Spell Unicode math back into the typed forms the writer uses.
        v = v.replace("\u221a", "sqrt")
             .replace("\u03c0", "pi")
             .replace("\u2212", "-")
             .replace("\u2013", "-")
             .replace("\u2014", "-")
             .replace("\u00d7", "*")
             .replace("\u00f7", "/")
             .replace("\u00b2", "^2")
             .replace("\u00b3", "^3")
             .replace("\u2044", "/");

        v = v.replaceAll("\\s+", "");
        v = v.replaceAll("[.]$", "");

        // "f(n)=17n+350" and "17n+350" are one answer; the solver is asked for the bare
        // value but often restates the function it just built.
        v = FUNCTION_PREFIX.matcher(v).replaceFirst("");

        // Fold "sqrt(3)" and "sqrt3" together, but only when the radicand has no nested
        // parentheses of its own, so grouping that carries meaning is left alone.
        v = SQRT_PARENS.matcher(v).replaceAll("sqrt$1");
        return v;
    }

    /** Parses plain integers, decimals, percentages, and simple fractions. */
    static Double parseNumeric(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        boolean percent = v.endsWith("%");
        if (percent) {
            v = v.substring(0, v.length() - 1).trim();
        }
        Double result = null;
        if (v.contains("/")) {
            String[] parts = v.split("/", 2);
            try {
                double denominator = Double.parseDouble(parts[1].trim());
                if (denominator != 0) {
                    result = Double.parseDouble(parts[0].trim()) / denominator;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        } else {
            try {
                result = Double.parseDouble(v);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (result != null && percent) {
            result = result / 100.0;
        }
        return result;
    }

    /**
     * Builds the A-D choice array for an answer-first question, dropping the verified
     * answer into a position derived from the stem so the correct letter is not always
     * the same one. Returns the assembled choices; the caller reads the winning id back
     * out with {@link #idOfChoiceText}.
     */
    public static JSONArray assembleChoices(String correctAnswer, List<String> distractors)
            throws GenerationException {
        List<String> texts = new ArrayList<>();
        texts.add(correctAnswer.trim());
        for (String distractor : distractors) {
            String d = distractor == null ? "" : distractor.trim();
            if (d.isEmpty()) {
                throw new GenerationException("distractor was blank");
            }
            for (String existing : texts) {
                if (answersMatch(existing, d)) {
                    throw new GenerationException("distractor duplicates another choice: " + d);
                }
            }
            texts.add(d);
        }
        if (texts.size() < 4) {
            throw new GenerationException("expected 3 distractors, got " + (texts.size() - 1));
        }

        int slot = Math.floorMod(correctAnswer.hashCode(), texts.size());
        String correct = texts.remove(0);
        texts.add(slot, correct);

        String[] ids = {"A", "B", "C", "D", "E", "F"};
        JSONArray choices = new JSONArray();
        for (int i = 0; i < texts.size(); i++) {
            choices.put(new JSONObject().put("id", ids[i]).put("text", texts.get(i)));
        }
        return choices;
    }

    /** Finds the id of the choice whose text matches {@code text}. */
    public static String idOfChoiceText(JSONArray choices, String text) throws GenerationException {
        for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.getJSONObject(i);
            if (answersMatch(choice.optString("text", ""), text)) {
                return choice.optString("id", "");
            }
        }
        throw new GenerationException("assembled choices do not contain the verified answer");
    }

    /** Generated content must be plain text; markup is a sign the model copied the seed. */
    public static boolean containsMarkup(String text) {
        return text != null && HTML_TAG.matcher(text).find();
    }

    private static String normalizeWhitespace(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }
}
