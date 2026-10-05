package net.java21.data2flow.ai.events;

import net.java21.data2flow.ai.commentary.CommentaryRepository;
import net.java21.data2flow.ai.commentary.CommentaryService;
import net.java21.data2flow.ai.commentary.SubjectLoader;
import net.java21.data2flow.ai.llm.LlmGateway;
import net.java21.data2flow.ai.settings.AiSettingsRepository;
import net.java21.data2flow.contracts.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * EVT-ANA-01 {@code analytics.run.succeeded} → 조직 설정 "분석 완료 시 자동 해설"(auto_commentary)이 켜져 있으면 해설을 만든다
 * (UC-AIA-01 대안 흐름 1a). 최소 1회 전달이므로 같은 실행에 해설이 이미 있으면 건너뛴다(멱등).
 *
 * <p>봉투 {@code {v, messageId, type, organizationId, occurredAt, payload{runId, analysisId, status, …}}}를 직접 읽는다
 * (contracts {@code EventType}에 analytics 이벤트가 아직 없음 — 계약 추가 요청).
 * 한도 초과·제공자 장애·AI 꺼짐은 다시 시도하지 않고 기록만 한다(해설은 사용자가 화면에서 다시 만들 수 있음).
 */
public class AutoCommentaryHandler {

    public static final String ROUTING_KEY = "analytics.run.succeeded";
    private static final Logger log = LoggerFactory.getLogger(AutoCommentaryHandler.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AiSettingsRepository settings;
    private final CommentaryRepository commentaries;
    private final CommentaryService service;
    private final SubjectLoader loader;
    private final LlmGateway gateway;

    public AutoCommentaryHandler(AiSettingsRepository settings, CommentaryRepository commentaries, CommentaryService service, SubjectLoader loader,
                                 LlmGateway gateway) {
        this.settings = settings;
        this.commentaries = commentaries;
        this.service = service;
        this.loader = loader;
        this.gateway = gateway;
    }

    /** @return 해설을 만들었으면 true */
    public boolean handle(byte[] body) {
        JsonNode event;
        try {
            event = JSON.readTree(body);
        } catch (RuntimeException e) {
            log.warn("EVT-ANA-01 형식 오류, 버림");
            return false;
        }
        String type = event.path("type").asString("");
        JsonNode payload = event.path("payload");
        if (!ROUTING_KEY.equals(type) && !"SUCCEEDED".equals(payload.path("status").asString())) {
            return false;
        }
        long org = event.path("organizationId").asLong(0);
        String runText = payload.path("runId").asString("");
        if (org <= 0 || !runText.matches("\\d{1,19}")) {
            return false;
        }
        long runId = Long.parseLong(runText);
        if (!settings.existsAutoCommentaryByOrganizationId(org) || !gateway.available(org)) {
            return false;
        }
        if (commentaries.findLatestDoneByOrganizationIdAndSubject(org, CommentaryService.SUBJECT_ANALYSIS_RUN, runId).isPresent()) {
            return false;
        }
        try {
            JsonNode subject = loader.loadRunForOrganization(org, runId);
            service.generateAndStore(org, null, runId, subject.path("result"));
            return true;
        } catch (BusinessException e) {
            log.info("자동 해설 건너뜀 org={} run={} code={}", org, runId, e.getErrorCode().code());
            return false;
        }
    }
}
