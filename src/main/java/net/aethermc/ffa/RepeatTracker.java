package net.aethermc.ffa;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window event counter keyed by string. Used for repeat-kill and abuse detection. */
public final class RepeatTracker {
    private final Map<String, Deque<Long>> events = new ConcurrentHashMap<>();

    /** Records an event and returns how many events (including this one) are inside the window. */
    public int record(String key, long now, long windowMs) {
        Deque<Long> q = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now, windowMs);
            q.addLast(now);
            return q.size();
        }
    }

    /** Counts events inside the window without recording. */
    public int count(String key, long now, long windowMs) {
        Deque<Long> q = events.get(key);
        if (q == null) return 0;
        synchronized (q) { prune(q, now, windowMs); return q.size(); }
    }

    public long lastEvent(String key) {
        Deque<Long> q = events.get(key);
        if (q == null) return 0;
        synchronized (q) { return q.isEmpty() ? 0 : q.peekLast(); }
    }

    private static void prune(Deque<Long> q, long now, long windowMs) {
        while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
    }

    /** Drops stale keys so the map cannot grow without bound. */
    public void cleanup(long now, long windowMs) {
        Iterator<Map.Entry<String, Deque<Long>>> it = events.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> q = it.next().getValue();
            synchronized (q) { prune(q, now, windowMs); if (q.isEmpty()) it.remove(); }
        }
    }

    public int size() { return events.size(); }
}
