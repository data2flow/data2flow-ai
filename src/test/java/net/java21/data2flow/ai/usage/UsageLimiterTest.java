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
}
