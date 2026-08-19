package satapp.session;

import java.util.concurrent.ConcurrentHashMap;

public class SessionStore {

    public static final String COOKIE = "sat_session";

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public SessionState get(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> new SessionState());
    }
}
