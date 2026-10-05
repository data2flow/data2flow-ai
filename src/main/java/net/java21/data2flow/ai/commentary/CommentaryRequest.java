package net.java21.data2flow.ai.commentary;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * API-AIA-01 요청 {@code {subjectType, subjectId, regenerate}}. {@code analysisId}는 선택(추가 필드): 화면이 알고 있으면 넘겨
 * 실행 ID로 분석을 찾는 내부 조회를 줄인다.
 */
public record CommentaryRequest(@NotBlank String subjectType, @NotNull @Pattern(regexp = "\\d{1,19}") String subjectId, boolean regenerate,
                                @Pattern(regexp = "\\d{1,19}") String analysisId) {
}
