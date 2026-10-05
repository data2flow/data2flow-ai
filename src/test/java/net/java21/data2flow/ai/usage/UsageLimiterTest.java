package net.java21.data2flow.ai.usage;

import net.java21.data2flow.ai.common.AiErrorCode;
import net.java21.data2flow.ai.llm.LlmProvider;
import net.java21.data2flow.ai.settings.AiSettings;
import net.java21.data2flow.ai.support.MutableClock;
import net.java21.data2flow.contracts.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AIA-07.04 사용량 한도(BR-AIA-08) — TC-AIA-062 */
class UsageLimiterTest {

    static AiSettings settings(int org, int perOrg, long tokens, int perUser) {
        return new AiSettings(org, true, LlmProvider.FAKE, "m", "e", perOrg, tokens, perUser, 90, false, new BigDecimal("0.9"), 30, 1, null, true);
    }

    @Test
    @DisplayName("[AIA-07.04][AT-AIA-07.1][TC-AIA-062] 조직 한도 100: 100회 뒤 101번째 AI_QUOTA_EXCEEDED, 한국 시간 자정에 초기화")
    void organizationLimitAndMidnightReset() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-05T14:00:00Z"));   // KST 23:00
        UsageLimiter limiter = new UsageLimiter(new InMemoryCounterStore(clock), clock, ZoneId.of("Asia/Seoul"));
        AiSettings s = settings(1, 100, 1_000_000, 1000);
        for (int i = 0; i < 100; i++) {
            limiter.acquire(s, (long) i);
        }
        assertThatThrownBy(() -> limiter.acquire(s, 999L)).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(AiErrorCode.AI_QUOTA_EXCEEDED));
        assertThat(limiter.resetAt()).isEqualTo(Instant.parse("2026-10-05T15:00:00Z"));
        clock.advance(Duration.ofHours(1));   // KST 자정
        limiter.acquire(s, 999L);
        assertThat(limiter.usedRequestsToday(1)).isEqualTo(1);
    }

    @Test
    @DisplayName("[AIA-07.04][TC-AIA-062] 사용자 한도와 토큰 한도는 따로 센다, 시스템 작업(사용자 없음)은 조직 한도만")
    void userAndTokenLimits() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-05T01:00:00Z"));
        UsageLimiter limiter = new UsageLimiter(new InMemoryCounterStore(clock), clock, ZoneId.of("Asia/Seoul"));
        AiSettings s = settings(2, 100, 1000, 2);
        limiter.acquire(s, 7L);
        limiter.acquire(s, 7L);
        assertThatThrownBy(() -> limiter.acquire(s, 7L)).isInstanceOf(BusinessException.class);
        limiter.acquire(s, 8L);
        limiter.acquire(s, null);
        limiter.addTokens(2, 1000);
        limiter.addTokens(2, 0);
        assertThat(limiter.usedTokensToday(2)).isEqualTo(1000);
        assertThatThrownBy(() -> limiter.acquire(s, 9L)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("[AIA-07.04][EVT-AIA-03] 한도 도달 시 ai.quota.exceeded를 한도마다 하루 한 번(조직 요청·사용자 요청·조직 토큰), 발행 실패면 다음 초과 때 다시")
    void quotaExceededEventOncePerLimitPerDay() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-05T01:00:00Z"));   // KST 10:00
        java.util.List<net.java21.data2flow.contracts.message.event.AiQuotaExceeded> sent = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean();
        UsageLimiter limiter = new UsageLimiter(new InMemoryCounterStore(clock), clock, ZoneId.of("Asia/Seoul"), (org, event, onFailure) -> {
            assertThat(org).isEqualTo(3L);
            if (fail.get()) {
                onFailure.run();
                return;
            }
            sent.add(event);
        });
        AiSettings s = settings(3, 3, 1000, 1);
        limiter.acquire(s, 7L);
        // 사용자 한도(1) → USER·REQUESTS, 두 번째 초과는 다시 내지 않음
        assertThatThrownBy(() -> limiter.acquire(s, 7L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> limiter.acquire(s, 7L)).isInstanceOf(BusinessException.class);
        assertThat(sent).singleElement().satisfies(e -> {
            assertThat(e.scope()).isEqualTo(net.java21.data2flow.contracts.message.event.AiQuotaExceeded.Scope.USER);
            assertThat(e.userId()).isEqualTo(7L);
            assertThat(e.limitType()).isEqualTo(net.java21.data2flow.contracts.message.event.AiQuotaExceeded.LimitType.REQUESTS);
            assertThat(e.resetAt()).isEqualTo(Instant.parse("2026-10-05T15:00:00Z"));
        });
        // 조직 한도(3) → ORG·REQUESTS. 첫 발행이 실패하면 표시를 되돌려 다음 초과 때 낸다
        limiter.acquire(s, 8L);
        limiter.acquire(s, null);
        fail.set(true);
        assertThatThrownBy(() -> limiter.acquire(s, 9L)).isInstanceOf(BusinessException.class);
        assertThat(sent).hasSize(1);
        fail.set(false);
        assertThatThrownBy(() -> limiter.acquire(s, 9L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> limiter.acquire(s, 9L)).isInstanceOf(BusinessException.class);
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).scope()).isEqualTo(net.java21.data2flow.contracts.message.event.AiQuotaExceeded.Scope.ORG);
        assertThat(sent.get(1).userId()).isNull();
        // 다음 날(자정 뒤) 같은 한도에 다시 닿으면 다시 낸다
        clock.advance(Duration.ofDays(1));
        for (int i = 0; i < 3; i++) {
            limiter.acquire(s, (long) (100 + i));
        }
        assertThatThrownBy(() -> limiter.acquire(s, 200L)).isInstanceOf(BusinessException.class);
        assertThat(sent).hasSize(3);
    }

    @Test
    @DisplayName("[AIA-07.04][EVT-AIA-03] 토큰 한도 도달 → ORG·TOKENS, 발행 쪽 예외가 나도 응답은 429 그대로")
    void tokenLimitEventAndPublisherErrorIgnored() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-05T01:00:00Z"));
        java.util.List<net.java21.data2flow.contracts.message.event.AiQuotaExceeded> sent = new java.util.ArrayList<>();
        UsageLimiter limiter = new UsageLimiter(new InMemoryCounterStore(clock), clock, ZoneId.of("Asia/Seoul"), (org, event, onFailure) -> {
            sent.add(event);
            throw new IllegalStateException("broker down");
        });
        AiSettings s = settings(4, 100, 10, 100);
        limiter.addTokens(4, 10);
        assertThatThrownBy(() -> limiter.acquire(s, 7L)).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(AiErrorCode.AI_QUOTA_EXCEEDED));
        assertThat(sent).singleElement().satisfies(e -> {
            assertThat(e.scope()).isEqualTo(net.java21.data2flow.contracts.message.event.AiQuotaExceeded.Scope.ORG);
            assertThat(e.limitType()).isEqualTo(net.java21.data2flow.contracts.message.event.AiQuotaExceeded.LimitType.TOKENS);
        });
    }
}
