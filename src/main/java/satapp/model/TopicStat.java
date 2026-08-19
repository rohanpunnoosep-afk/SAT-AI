package satapp.model;

public class TopicStat {

    private final String domain;
    private final String skill;
    private int attempts;
    private int correct;

    public TopicStat(String domain, String skill) {
        this.domain = domain;
        this.skill = skill;
    }

    public void record(boolean wasCorrect) {
        attempts++;
        if (wasCorrect) {
            correct++;
        }
    }

    public double accuracy() {
        if (attempts == 0) {
            return 0.0;
        }
        return (double) correct / (double) attempts;
    }

    public String getDomain() {
        return domain;
    }

    public String getSkill() {
        return skill;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getCorrect() {
        return correct;
    }
}
