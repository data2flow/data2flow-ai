package net.java21.data2flow.ai.settings;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import net.java21.data2flow.ai.llm.LlmProvider;

import java.math.BigDecimal;

/** API-AIA-07 PUT 본문(전체 교체, {@code baseVersion} 필수) */
public record AiSettingsRequest(@NotNull Boolean enabled, @NotNull LlmProvider provider, @NotBlank @Size(max = 64) String model,
                                @Size(max = 64) String embeddingModel,
                                @NotNull @Min(0) @Max(1_000_000) Integer dailyRequestLimit,
                                @NotNull @Min(0) Long dailyTokenLimit,
                                @NotNull @Min(0) @Max(1_000_000) Integer perUserDailyLimit,
                                @NotNull @Min(7) @Max(365) Integer logRetentionDays,
                                @NotNull Boolean autoCommentary,
                                @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal evalThreshold,
                                @NotNull @Min(5) @Max(120) Integer suggestionTtlMinutes,
                                @NotNull Integer baseVersion) {
}
