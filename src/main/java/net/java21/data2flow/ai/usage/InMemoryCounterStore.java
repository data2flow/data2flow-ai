package net.java21.data2flow.ai.usage;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 메모리 카운터(시험, Redis 장애 시 파드별 대체). 동기화로 원자성을 지킨다 */
public class InMemoryCounterStore implements CounterStore {

    private final Clock clock;
    private final Map<String, Entry> values = new HashMap<>();

    public InMemoryCounterStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized int acquire(List<String> keys, List<Long> limits, List<String> guardKeys, List<Long> guards, Instant expireAt) {
        for (int i = 0; i < keys.size(); i++) {
            if (current(keys.get(i)) >= limits.get(i)) {
                return i;
            }
        }
        for (int i = 0; i < guardKeys.size(); i++) {
            if (current(guardKeys.get(i)) >= guards.get(i)) {
                return keys.size() + i;
            }
        }
        for (String key : keys) {
            values.put(key, new Entry(current(key) + 1, expireAt));
        }
        return -1;
    }

    @Override
    public synchronized long add(String key, long delta, Instant expireAt) {
        long next = current(key) + delta;
        values.put(key, new Entry(next, expireAt));
        return next;
    }

    @Override
    public synchronized long get(String key) {
        return current(key);
    }

    private long current(String key) {
        Entry e = values.get(key);
        if (e == null || !clock.instant().isBefore(e.expireAt())) {
            values.remove(key);
            return 0;
        }
        return e.value();
    }

    private record Entry(long value, Instant expireAt) {
    }
}
