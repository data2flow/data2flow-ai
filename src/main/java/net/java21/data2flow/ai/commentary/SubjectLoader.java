package net.java21.data2flow.ai.commentary;

import net.java21.data2flow.ai.common.AiErrorCode;
import net.java21.data2flow.ai.common.AnalyticsClient;
import net.java21.data2flow.ai.common.CoreClient;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import tools.jackson.databind.JsonNode;

/**
 * 해설 대상(분석 실행 결과)을 읽는다.
 *
 * <ol>
 *   <li>분석 ID를 모르면 analytics API-ANA-34로 실행의 {@code analysisId}만 찾는다(조직 단위).</li>
 *   <li>결과는 core API-ANA-09 {@code GET /core/analytics/analyses/{analysis-id}/runs/{run-id}}를 <b>요청 사용자로</b> 읽는다
 *       (권한 밖 결과는 404, BR-AIA-05).</li>
 * </ol>
 * 성공(SUCCEEDED)이 아닌 실행은 400 AI_COMMENTARY_SUBJECT_INVALID.
 */
public class SubjectLoader {

    private final CoreClient core;
    private final AnalyticsClient analytics;

    public SubjectLoader(CoreClient core, AnalyticsClient analytics) {
        this.core = core;
        this.analytics = analytics;
    }

    /** {@code {run, result}} */
    public JsonNode loadRun(CurrentUser user, long runId, Long analysisId) {
        long analysis = analysisId != null ? analysisId : analysisIdOf(user.organizationId(), runId);
        JsonNode body = core.getAsUser(user, b -> b.path("/core/analytics/analyses/{a}/runs/{r}").build(analysis, runId),
                CommonErrorCode.RESOURCE_NOT_FOUND);
        JsonNode r = body == null ? null : body.path("response");
        return requireSucceeded(r);
    }

    /** 요청 밖(자동 해설): 조직 단위로 analytics에서 읽는다 */
    public JsonNode loadRunForOrganization(long organizationId, long runId) {
        return requireSucceeded(analytics.run(organizationId, runId, CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private long analysisIdOf(long organizationId, long runId) {
        JsonNode run = analytics.run(organizationId, runId, CommonErrorCode.RESOURCE_NOT_FOUND).path("run");
        String id = run.path("analysisId").asString(null);
        if (id == null || !id.matches("\\d{1,19}")) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return Long.parseLong(id);
    }

    static JsonNode requireSucceeded(JsonNode r) {
        if (r == null || r.isMissingNode() || r.isNull()) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!"SUCCEEDED".equals(r.path("run").path("status").asString()) || r.path("result").isMissingNode() || r.path("result").isNull()) {
            throw new BusinessException(AiErrorCode.AI_COMMENTARY_SUBJECT_INVALID);
        }
        return r;
    }
}
