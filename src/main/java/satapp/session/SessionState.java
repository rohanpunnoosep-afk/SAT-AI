package satapp.session;

import satapp.model.Question;
import satapp.model.TopicStat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionState {

    public static class AnswerRecord {
        public final String submitted;
        public final boolean correct;
        public final long timestamp;

        public AnswerRecord(String submitted, boolean correct, long timestamp) {
            this.submitted = submitted;
            this.correct = correct;
            this.timestamp = timestamp;
        }
    }

    private final Map<String, AnswerRecord> answeredQuestions = new ConcurrentHashMap<>();
    private final Map<String, TopicStat> topicStats = new ConcurrentHashMap<>();

    public void record(Question q, String submitted, boolean correct) {
        answeredQuestions.put(q.getId(), new AnswerRecord(submitted, correct, System.currentTimeMillis()));
        String key = q.getDomain() + "::" + q.getSkill();
        TopicStat stat = topicStats.computeIfAbsent(key, k -> new TopicStat(q.getDomain(), q.getSkill()));
        synchronized (stat) {
            stat.record(correct);
        }
    }

    public List<TopicStat> reviewList() {
        List<TopicStat> weak = new ArrayList<>();
        for (TopicStat stat : topicStats.values()) {
            if (stat.getAttempts() >= 2 && stat.accuracy() < 0.7) {
                weak.add(stat);
            }
        }
        weak.sort(Comparator.comparingDouble(TopicStat::accuracy)
                .thenComparing(Comparator.comparingInt(TopicStat::getAttempts).reversed()));
        return weak;
    }

    public List<TopicStat> allTopics() {
        return new ArrayList<>(topicStats.values());
    }

    public Map<String, AnswerRecord> getAnsweredQuestions() {
        return answeredQuestions;
    }
}
