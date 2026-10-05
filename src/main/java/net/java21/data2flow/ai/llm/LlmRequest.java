package net.java21.data2flow.ai.llm;

import java.util.List;
import java.util.Objects;

/**
 * LLM 요청 한 건. 시스템 지시({@code task})는 코드가 정한 문장만, 사용자·기기에서 온 값은 {@code data}에만 넣는다(BR-AIA-03).
 *
 * @param organizationId 조직(한도·설정·사용량)
 * @param userId         요청 사용자(없으면 시스템 작업, 예: 자동 해설)
 * @param feature        사용량 기능 구분
 * @param task           이 요청의 지시(코드가 만든 문장)
 * @param data           데이터 구획
 * @param maxTokens      출력 토큰 상한
 */
public record LlmRequest(long organizationId, Long userId, LlmFeature feature, String task, List<DataSection> data, int maxTokens) {

    public LlmRequest {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(task, "task");
        data = data == null ? List.of() : List.copyOf(data);
        maxTokens = maxTokens <= 0 ? 2048 : maxTokens;
    }
}
