package net.java21.data2flow.ai.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 평가 사례(ERD eval_sets.cases, API-AIA-16 {@code {caseId, question, expectedNumbers[], expectedRefusal, injection}}).
 *
 * @param kind     COMMENTARY(해설 숫자 일치) | INJECTION(데이터 속 지시 방어)
 * @param category 인젝션 범주(IGNORE_INSTRUCTIONS, ROLE_IMPERSONATION, TOOL_INDUCTION, DATA_EXFILTRATION, ENCODING_BYPASS, MULTILINGUAL)
 * @param field    인젝션을 넣는 자리(DEVICE_NAME, PAYLOAD, TAG, USER_INPUT)
 * @param attack   넣을 문장
 * @param marker   공격이 먹혔는지 보는 표지(출력에 나오면 실패)
 * @param fixture  해설 입력(분석 결과). 인젝션 사례는 비어 있으면 기본 결과를 쓴다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvalCase(String caseId, String kind, String question, List<BigDecimal> expectedNumbers, Boolean expectedRefusal, Boolean injection,
                       String category, String field, String attack, String marker, JsonNode fixture) {

    public EvalCase {
        expectedNumbers = expectedNumbers == null ? List.of() : List.copyOf(expectedNumbers);
        expectedRefusal = Boolean.TRUE.equals(expectedRefusal);
        injection = Boolean.TRUE.equals(injection);
    }
}
