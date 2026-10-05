package net.java21.data2flow.ai.common;

import net.java21.data2flow.contracts.error.CommonErrorCode;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * pipeline 스크립트 내부 API(호출자 core-api·ai, design/api/SCR-api.md §2).
 *
 * <ul>
 *   <li>API-SCR-30 {@code POST /internal/pipeline/scripts/check} {@code {kind, code, moduleRefs?, organizationId?}} →
 *       {@code {ok, problems[{line, col, severity, code, message}]}}</li>
 *   <li>API-SCR-31 {@code POST /internal/pipeline/scripts/test-run}
 *       {@code {kind, code, input, context?, scriptId?, rawMessageId?, organizationId, moduleRefs?}} →
 *       {@code {ok, output, diff, logs[], durationMs, outputBytes, error?}}. 아무것도 저장하지 않는다(BR-SCR-08)</li>
 * </ul>
 */
public class PipelineClient {

    private final InternalHttp http;

    public PipelineClient(InternalHttp http) {
        this.http = http;
    }

    public JsonNode check(long organizationId, String kind, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("code", code);
        body.put("organizationId", organizationId);
        return http.post(null, "/internal/pipeline/scripts/check", body, CommonErrorCode.RESOURCE_NOT_FOUND).path("response");
    }

    public JsonNode testRun(long organizationId, String kind, String code, JsonNode input, Long rawMessageId, Long scriptId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("code", code);
        if (input != null && !input.isNull() && !input.isMissingNode()) {
            body.put("input", input);
        }
        if (rawMessageId != null) {
            body.put("rawMessageId", rawMessageId);
        }
        if (scriptId != null) {
            body.put("scriptId", scriptId);
        }
        body.put("organizationId", organizationId);
        return http.post(null, "/internal/pipeline/scripts/test-run", body, CommonErrorCode.RESOURCE_NOT_FOUND).path("response");
    }
}
