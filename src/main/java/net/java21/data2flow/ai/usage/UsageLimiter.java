package net.java21.data2flow.ai.usage;

import net.java21.data2flow.ai.common.AiErrorCode;
import net.java21.data2flow.ai.settings.AiSettings;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.message.event.AiQuotaExceeded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p>한도에 닿으면 EVT-AIA-03 {@code ai.quota.exceeded}를 낸다. 같은 한도(조직 요청·사용자 요청·조직 토큰)마다 하루 한 번만 내도록
 * 카운터 키 {@code …:evt:…}로 표시하고, 발행이 실패하면 표시를 되돌려 다음 초과 때 다시 낸다.
 */
public class UsageLimiter {

    private static final Logger log = LoggerFactory.getLogger(UsageLimiter.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private final CounterStore counters;
    private final Clock clock;
    private final ZoneId zone;
    private final QuotaEvents events;
    /** 이 파드에서 이미 알린 한도 표시(만료 시각). 거부마다 Redis를 더 부르지 않게 먼저 본다(동시 요청 200건, TC-AIA-063) */
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> notified = new java.util.concurrent.ConcurrentHashMap<>();

    public UsageLimiter(CounterStore counters, Clock clock, ZoneId zone) {
        this(counters, clock, zone, QuotaEvents.LOG_ONLY);
    }

    public UsageLimiter(CounterStore counters, Clock clock, ZoneId zone, QuotaEvents events) {
        this.counters = counters;
        this.clock = clock;
        this.zone = zone;
        this.events = events;
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
        Instant resetAt = resetAt();
        int over = counters.acquire(keys, limits, List.of(key(s.organizationId(), day, "tok")), List.of(s.dailyTokenLimit()), resetAt);
        if (over >= 0) {
            AiQuotaExceeded event = over == 0 ? AiQuotaExceeded.organization(AiQuotaExceeded.LimitType.REQUESTS, resetAt)
                    : over < keys.size() ? AiQuotaExceeded.user(userId, AiQuotaExceeded.LimitType.REQUESTS, resetAt)
                    : AiQuotaExceeded.organization(AiQuotaExceeded.LimitType.TOKENS, resetAt);
            notifyOnce(s.organizationId(), day, event, resetAt);
            throw new BusinessException(AiErrorCode.AI_QUOTA_EXCEEDED);
        }
    }

    /** EVT-AIA-03을 같은 한도마다 하루 한 번만 낸다. 발행 실패·예외는 429 응답을 바꾸지 않는다 */
    private void notifyOnce(long organizationId, String day, AiQuotaExceeded event, Instant resetAt) {
        String marker = key(organizationId, day, "evt:" + event.scope() + ":" + event.limitType()
                + (event.userId() == null ? "" : ":u:" + event.userId()));
        Instant now = clock.instant();
        notified.values().removeIf(expires -> !now.isBefore(expires));
        if (notified.putIfAbsent(marker, resetAt) != null) {
            return;
        }
        try {
            if (counters.add(marker, 1, resetAt) != 1) {
                return;
            }
            events.exceeded(organizationId, event, () -> {
                notified.remove(marker);
                counters.add(marker, -1, resetAt);
            });
        } catch (RuntimeException e) {
            notified.remove(marker);
            log.warn("ai.quota.exceeded 발행 준비 실패 org={}", organizationId, e);
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
