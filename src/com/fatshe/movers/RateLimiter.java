package com.fatshe.movers;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Sliding-window limiter: at most {@code max} hits per key inside {@code windowMs}. */
final class RateLimiter {
    private final int max;
    private final long windowMs;
    private final Map<String, Deque<Long>> hits = new HashMap<>();

    RateLimiter(int max, long windowMs) {
        this.max = max;
        this.windowMs = windowMs;
    }

    synchronized boolean allow(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        trim(q, now);
        if (q.size() >= max) return false;
        q.addLast(now);
        if (hits.size() > 10_000) prune(now);
        return true;
    }

    synchronized long retryAfterSeconds(String key) {
        Deque<Long> q = hits.get(key);
        if (q == null || q.isEmpty()) return 1;
        long wait = windowMs - (System.currentTimeMillis() - q.peekFirst());
        return Math.max(1, (wait + 999) / 1000);
    }

    private void trim(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Deque<Long>>> it = hits.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> q = it.next().getValue();
            trim(q, now);
            if (q.isEmpty()) it.remove();
        }
    }
}
