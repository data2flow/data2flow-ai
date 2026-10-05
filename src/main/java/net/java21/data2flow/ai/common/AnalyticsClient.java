package net.java21.data2flow.ai.common;

import net.java21.data2flow.contracts.error.ErrorCode;
import tools.jackson.databind.JsonNode;

/**
 * analytics 내부 API.
 *
 * <ul>
 *   <li>API-ANA-34 {@code GET /internal/analytics/runs/{run-id}} → {@code {run, result}}. 사용자 요청에서는 실행 ID로 분석 ID만 찾고
 *       결과는 core API-ANA-09(사용자 위임, 권한·공간 범위 판정)로 다시 읽는다. 자동 해설(EVT-ANA-01, 요청 밖)은 조직 단위로 바로 읽는다.</li>
 * </ul>
 * 헤더는 {@code X-ORG-ID}·{@code X-CALLER-SERVICE: data2flow-ai}(ANA-api §2 헤더 규칙).
 */
public class AnalyticsClient {

    private final InternalHttp http;

    public AnalyticsClient(InternalHttp http) {
        this.http = http;
    }

    /** 실행과 결과({@code response.run}, {@code response.result}). 없거나 다른 조직이면 {@code notFound} */
    public JsonNode run(long organizationId, long runId, ErrorCode notFound) {
        return http.getForOrganization(organizationId, b -> b.path("/internal/analytics/runs/{id}").build(runId), notFound).path("response");
    }
}
