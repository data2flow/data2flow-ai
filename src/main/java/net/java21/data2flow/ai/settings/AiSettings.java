package net.java21.data2flow.ai.settings;

import net.java21.data2flow.ai.llm.LlmProvider;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 조직 AI 설정(data2flow_ai.ai_settings, API-AIA-07). 행이 없으면 기본값({@code version=0}, {@code persisted=false})이다.
 */
public record AiSettings(long organizationId, boolean enabled, LlmProvider provider, String model, String embeddingModel,
                         int dailyRequestLimit, long dailyTokenLimit, int perUserDailyLimit, int logRetentionDays,
                         boolean autoCommentary, BigDecimal evalThreshold, int suggestionTtlMinutes, int version,
                         Instant updatedAt, boolean persisted) {

    public static AiSettings defaults(long organizationId, LlmProvider provider, String model) {
        return new AiSettings(organizationId, true, provider, model, "hashing-1024", 1000, 2_000_000L, 100, 90, false,
                new BigDecimal("0.900"), 30, 0, null, false);
    }
}
