package net.java21.data2flow.ai.usage;

import net.java21.data2flow.ai.common.AiErrorCode;
import net.java21.data2flow.ai.settings.AiSettings;
import net.java21.data2flow.contracts.error.BusinessException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 조직·사용자 일일 한도(AIA-07.04, BR-AIA-08). 요청 수는 들어올 때 원자적으로 올리고, 토큰은 응답 뒤에 더한다
 * (오늘 쓴 토큰이 한도 이상이면 다음 요청부터 막는다). 한도는 조직 시간대 자정에 초기화된다.
 */
public class UsageLimiter {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private final CounterStore counters;
    private final Clock clock;
    private final ZoneId zone;

    public UsageLimiter(CounterStore counters, Clock clock, ZoneId zone) {
        this.counters = counters;
        this.clock = clock;
        this.zone = zone;
    }

    /** 한도 안이면 요청 1건을 센다. 넘으면 429 AI_QUOTA_EXCEEDED */
    public void acquire(AiSettings s, Long userId) {
        String day = today();
        List<String> keys = new ArrayList<>(List.of(key(s.organizationId(), day, "req")));
        List<Long> limits = new ArrayList<>(List.of((long) s.dailyRequestLimit()));
        if (userId != null) {
            keys.add(key(s.organizationId(), day, "u:" + userId + ":req"));
            limits.add((long) s.perUserDailyLimit());
        }
        int over = counters.acquire(keys, limits, List.of(key(s.organizationId(), day, "tok")), List.of(s.dailyTokenLimit()), resetAt());
        if (over >= 0) {
            throw new BusinessException(AiErrorCode.AI_QUOTA_EXCEEDED);
        }
    }

    public void addTokens(long organizationId, long tokens) {
        if (tokens > 0) {
            counters.add(key(organizationId, today(), "tok"), tokens, resetAt());
        }
    }

    public long usedRequestsToday(long organizationId) {
        return counters.get(key(organizationId, today(), "req"));
    }

    public long usedTokensToday(long organizationId) {
        return counters.get(key(organizationId, today(), "tok"));
    }

    /** 다음 초기화 시각(조직 시간대 자정) */
    public Instant resetAt() {
        return LocalDate.now(clock.withZone(zone)).plusDays(1).atStartOfDay(zone).toInstant();
    }

    private String today() {
        return LocalDate.now(clock.withZone(zone)).format(DAY);
    }

    private static String key(long org, String day, String suffix) {
        return "data2flow:ai:quota:" + org + ":" + day + ":" + suffix;
    }
}
