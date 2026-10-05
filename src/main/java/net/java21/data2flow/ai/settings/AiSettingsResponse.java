package net.java21.data2flow.ai.settings;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * API-AIA-07 응답. 문서 필드 + {@code providers}(제공자별 사용 가능 여부, "준비 중" 표시용 — 추가 필드).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiSettingsResponse(boolean enabled, String provider, String model, String embeddingModel, int dailyRequestLimit,
                                 long dailyTokenLimit, int perUserDailyLimit, int logRetentionDays, boolean autoCommentary,
                                 BigDecimal evalThreshold, int suggestionTtlMinutes, int version, Instant updatedAt,
                                 List<ProviderStatus> providers) {

    /** 제공자 상태: available=false면 화면에 "준비 중" */
    public record ProviderStatus(String provider, boolean available, boolean allowed, String note) {
    }

    static AiSettingsResponse of(AiSettings s, List<ProviderStatus> providers) {
        return new AiSettingsResponse(s.enabled(), s.provider().name(), s.model(), s.embeddingModel(), s.dailyRequestLimit(),
                s.dailyTokenLimit(), s.perUserDailyLimit(), s.logRetentionDays(), s.autoCommentary(), s.evalThreshold(),
                s.suggestionTtlMinutes(), s.version(), s.updatedAt(), providers);
    }
}
