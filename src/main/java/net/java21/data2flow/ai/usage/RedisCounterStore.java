package net.java21.data2flow.ai.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 카운터(INCR + EXPIREAT를 Lua 한 번으로, 동시 요청 200건에서도 한도만큼만 허용 — TC-AIA-063).
 * 키 접두사는 {@code data2flow:ai:}(ADR-022). Redis에 닿지 못하면 메모리 카운터로 대신한다(파드별로 세므로 느슨해지지만
 * AI 한도 장애가 다른 기능을 막지 않게, BR-AIA-08).
 */
public class RedisCounterStore implements CounterStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCounterStore.class);
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local n = tonumber(ARGV[1])
            local g = tonumber(ARGV[2])
            for i = 1, n do
              local v = tonumber(redis.call('GET', KEYS[i]) or '0')
              if v >= tonumber(ARGV[2 + i]) then return i - 1 end
            end
            for i = 1, g do
              local v = tonumber(redis.call('GET', KEYS[n + i]) or '0')
              if v >= tonumber(ARGV[2 + n + i]) then return n + i - 1 end
            end
            local exp = tonumber(ARGV[3 + n + g])
            for i = 1, n do
              redis.call('INCR', KEYS[i])
              redis.call('EXPIREAT', KEYS[i], exp)
            end
            return -1
            """, Long.class);
    private static final DefaultRedisScript<Long> ADD = new DefaultRedisScript<>("""
            local v = redis.call('INCRBY', KEYS[1], ARGV[1])
            redis.call('EXPIREAT', KEYS[1], ARGV[2])
            return v
            """, Long.class);

    private final StringRedisTemplate redis;
    private final CounterStore fallback;

    public RedisCounterStore(StringRedisTemplate redis, CounterStore fallback) {
        this.redis = redis;
        this.fallback = fallback;
    }

    @Override
    public int acquire(List<String> keys, List<Long> limits, List<String> guardKeys, List<Long> guards, Instant expireAt) {
        try {
            List<String> allKeys = new ArrayList<>(keys);
            allKeys.addAll(guardKeys);
            List<String> args = new ArrayList<>();
            args.add(Integer.toString(keys.size()));
            args.add(Integer.toString(guardKeys.size()));
            limits.forEach(l -> args.add(Long.toString(l)));
            guards.forEach(l -> args.add(Long.toString(l)));
            args.add(Long.toString(expireAt.getEpochSecond()));
            Long r = redis.execute(ACQUIRE, allKeys, args.toArray());
            return r == null ? -1 : r.intValue();
        } catch (RuntimeException e) {
            log.warn("Redis 카운터 실패, 메모리로 대신함: {}", e.getMessage());
            return fallback.acquire(keys, limits, guardKeys, guards, expireAt);
        }
    }

    @Override
    public long add(String key, long delta, Instant expireAt) {
        try {
            Long r = redis.execute(ADD, List.of(key), Long.toString(delta), Long.toString(expireAt.getEpochSecond()));
            return r == null ? 0 : r;
        } catch (RuntimeException e) {
            log.warn("Redis 카운터 실패, 메모리로 대신함: {}", e.getMessage());
            return fallback.add(key, delta, expireAt);
        }
    }

    @Override
    public long get(String key) {
        try {
            String v = redis.opsForValue().get(key);
            return v == null ? 0 : Long.parseLong(v);
        } catch (RuntimeException e) {
            return fallback.get(key);
        }
    }
}
