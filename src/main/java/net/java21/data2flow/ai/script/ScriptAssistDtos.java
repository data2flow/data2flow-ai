package net.java21.data2flow.ai.script;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** 스크립트 도우미 요청·응답(API-AIA-03, 내부 API-SCR-16 위임) */
public final class ScriptAssistDtos {

    private ScriptAssistDtos() {
    }

    /**
     * API-AIA-03 요청. {@code previousAttemptId}는 [고쳐 줘] 재요청일 때 이어 갈 도움 ID(assistId)다.
     */
    public record AssistRequest(@Pattern(regexp = "\\d{1,19}") String scriptId,
                                @NotNull @Pattern(regexp = "DECODE|TRANSFORM") String stage,
                                @NotNull @Valid Sample sample,
                                @NotBlank @Size(max = 2000) String requirement,
                                @Pattern(regexp = "\\d{1,19}") String previousAttemptId) {
    }

    /** 원본 메시지 ID 또는 붙여 넣은 payload 중 하나 */
    public record Sample(@Pattern(regexp = "\\d{1,19}") String rawMessageId, JsonNode payload) {
    }

    /** API-AIA-03 응답. {@code aiAssisted=true}: core가 버전 이력에 "AI 작성"을 남길 때 쓴다(BR-AIA-07a) */
    public record AssistResponse(String assistId, int attempt, String code, String explanation, TestResult test, boolean aiAssisted) {
    }

    /** 시험 결과(API-SCR-30 정적 검사 + API-SCR-31 시험 실행) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestResult(String status, JsonNode input, JsonNode output, JsonNode diff, JsonNode error, Double durationMs) {
    }

    /** 내부 API(API-SCR-16 위임) 요청 */
    public record DraftRequest(@NotNull @Pattern(regexp = "DECODE|TRANSFORM") String kind, @NotBlank @Size(max = 2000) String requirement,
                               @Size(max = 5) List<JsonNode> samples, String currentCode) {
    }

    /** 내부 API 응답 */
    public record DraftResponse(String code, String explanation) {
    }
}
