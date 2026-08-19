package satapp.model;

public class Question {

    private String id;
    private String externalId;
    private String section;
    private String domain;
    private String skill;
    private String difficulty;
    private String questionType;
    private String stimulus;
    private String stem;
    private String choicesJson;
    private String correctAnswer;
    private String explanation;
    private String source;
    private String parentQuestionId;

    public Question() {
    }

    public Question(String id, String externalId, String section, String domain, String skill,
                     String difficulty, String questionType, String stimulus, String stem,
                     String choicesJson, String correctAnswer, String explanation, String source,
                     String parentQuestionId) {
        this.id = id;
        this.externalId = externalId;
        this.section = section;
        this.domain = domain;
        this.skill = skill;
        this.difficulty = difficulty;
        this.questionType = questionType;
        this.stimulus = stimulus;
        this.stem = stem;
        this.choicesJson = choicesJson;
        this.correctAnswer = correctAnswer;
        this.explanation = explanation;
        this.source = source;
        this.parentQuestionId = parentQuestionId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getSkill() {
        return skill;
    }

    public void setSkill(String skill) {
        this.skill = skill;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getQuestionType() {
        return questionType;
    }

    public void setQuestionType(String questionType) {
        this.questionType = questionType;
    }

    public String getStimulus() {
        return stimulus;
    }

    public void setStimulus(String stimulus) {
        this.stimulus = stimulus;
    }

    public String getStem() {
        return stem;
    }

    public void setStem(String stem) {
        this.stem = stem;
    }

    public String getChoicesJson() {
        return choicesJson;
    }

    public void setChoicesJson(String choicesJson) {
        this.choicesJson = choicesJson;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(String correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getParentQuestionId() {
        return parentQuestionId;
    }

    public void setParentQuestionId(String parentQuestionId) {
        this.parentQuestionId = parentQuestionId;
    }
}
