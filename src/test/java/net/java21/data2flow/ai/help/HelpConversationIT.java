package net.java21.data2flow.ai.help;

import net.java21.data2flow.ai.support.AbstractIT;
import net.java21.data2flow.contracts.authz.BuiltinRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** AIA-09.01 제품 도움말(문서 색인 pgvector + 대화 HELP 모드), AIA-03.06 대화 소유·삭제 */
class HelpConversationIT extends AbstractIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final long ORG = 6;
    static final long VIEWER = 61;

    @Autowired
    HelpIndexer indexer;

    @BeforeEach
    void world() {
        grant(ORG, VIEWER, BuiltinRole.VIEWER);
        grant(ORG, 62, BuiltinRole.VIEWER);
        indexer.index();
    }

    @Test
    @DisplayName("[AIA-09.01][AT-AIA-09.1][TC-AIA-088] '규칙은 어떻게 만들어요?' → 사용자 가이드 근거 답과 문서 링크(citation)")
    void howTo() throws Exception {
        HttpResponse<String> res = post("/ai/conversations", ORG, VIEWER, "{\"content\":\"규칙은 어떻게 만들어요?\",\"mode\":\"HELP\"}");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("event:conversation").contains("event:citation").contains("/help/guide/rules")
                .contains("https://data2flow.test");
        assertThat(FAKE.capturedTexts().getFirst()).contains("<data id=\"docs\">").contains("[HELP]");
    }

    @Test
    @DisplayName("[AIA-09.02][TC-AIA-090] 화면 맥락의 오류 코드가 검색에 들어가 그 오류 코드 설명이 먼저 근거가 된다")
    void errorCodeContext() throws Exception {
        HttpResponse<String> res = post("/ai/conversations", ORG, VIEWER,
                "{\"content\":\"이 오류는 무슨 뜻이죠?\",\"mode\":\"HELP\",\"context\":{\"screenId\":\"FLOW_EDITOR\",\"errorCode\":\"AI_QUOTA_EXCEEDED\"}}");
        assertThat(res.body()).contains("/help/errors#AI_QUOTA_EXCEEDED");
        assertThat(FAKE.capturedTexts().getFirst()).contains("<data id=\"context\">").contains("AI_QUOTA_EXCEEDED");
    }

    @Test
    @DisplayName("[AIA-09.01][TC-AIA-088] 문서와 관계없는 질문은 LLM을 부르지 않고 '문서에서 찾지 못했습니다'")
    void notFound() throws Exception {
        HttpResponse<String> res = post("/ai/conversations", ORG, VIEWER, "{\"content\":\"qzxv wplk\",\"mode\":\"HELP\"}");
        assertThat(res.body()).contains("문서에서 찾지 못했습니다");
        assertThat(FAKE.captured()).isEmpty();
    }

    @Test
    @DisplayName("[AIA-09.01] LLM을 쓸 수 없으면(NONE) 찾은 문서 발췌와 링크만")
    void noLlm() throws Exception {
        jdbc.update("INSERT INTO data2flow_ai.ai_settings (organization_id, enabled, provider, model, version) VALUES (?, true, 'NONE', 'x', 1)", ORG);
        HttpResponse<String> res = post("/ai/conversations", ORG, VIEWER, "{\"content\":\"플로우 시뮬레이션은 어떻게 하나요?\",\"mode\":\"HELP\"}");
        assertThat(res.body()).contains("AI 답변을 쓸 수 없어").contains("/help/guide/flows");
        assertThat(FAKE.captured()).isEmpty();
    }

    @Test
    @DisplayName("[AIA-03.06][AT-AIA-03.5][TC-AIA-035][TC-AIA-036] 이어서 묻기, 남의 대화 404, 전체 삭제 후 목록이 비고 사용량은 남는다, DATA 모드는 아직 400")
    void conversationsLifecycle() throws Exception {
        HttpResponse<String> first = post("/ai/conversations", ORG, VIEWER, "{\"content\":\"스크립트 이슬점\",\"mode\":\"HELP\"}");
        String id = first.body().lines().filter(l -> l.startsWith("data:") && l.contains("conversationId")).findFirst().orElseThrow()
                .replaceAll(".*\"conversationId\":\"(\\d+)\".*", "$1");
        assertThat(post("/ai/conversations/" + id + "/messages", ORG, VIEWER, "{\"content\":\"분석은 어떻게 실행하나요?\"}").statusCode())
                .isEqualTo(200);
        JsonNode detail = JSON.readTree(get("/ai/conversations/" + id, ORG, VIEWER).body()).path("response");
        assertThat(detail.path("messages").size()).isEqualTo(4);
        assertThat(detail.path("mode").asString()).isEqualTo("HELP");

        assertThat(get("/ai/conversations/" + id, ORG, 62).statusCode()).isEqualTo(404);
        assertThat(get("/ai/conversations/" + id, ORG, 62).body()).contains("AI_CONVERSATION_NOT_FOUND");
        assertThat(delete("/ai/conversations/" + id, ORG, 62).statusCode()).isEqualTo(404);
        assertThat(post("/ai/conversations/999999/messages", ORG, VIEWER, "{\"content\":\"x\"}").statusCode()).isEqualTo(404);
        assertThat(post("/ai/conversations", ORG, VIEWER, "{\"content\":\"어제 CO2 최고치?\",\"mode\":\"DATA\"}").statusCode()).isEqualTo(400);

        post("/ai/conversations", ORG, VIEWER, "{\"content\":\"MCP 연결\",\"mode\":\"HELP\"}");
        assertThat(JSON.readTree(get("/ai/conversations", ORG, VIEWER).body()).path("totalCount").asLong()).isEqualTo(2);
        long usageBefore = jdbc.queryForObject("SELECT count(*) FROM data2flow_ai.usage_logs WHERE organization_id = ?", Long.class, ORG);
        assertThat(post("/ai/conversations/delete-all", ORG, VIEWER, "{}").statusCode()).isEqualTo(204);
        assertThat(JSON.readTree(get("/ai/conversations", ORG, VIEWER).body()).path("totalCount").asLong()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data2flow_ai.messages WHERE organization_id = ?", Long.class, ORG)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data2flow_ai.usage_logs WHERE organization_id = ?", Long.class, ORG))
                .isEqualTo(usageBefore);

        HttpResponse<String> again = post("/ai/conversations", ORG, VIEWER, "{\"content\":\"규칙 알림\",\"mode\":\"HELP\"}");
        String id2 = again.body().lines().filter(l -> l.startsWith("data:") && l.contains("conversationId")).findFirst().orElseThrow()
                .replaceAll(".*\"conversationId\":\"(\\d+)\".*", "$1");
        assertThat(delete("/ai/conversations/" + id2, ORG, VIEWER).statusCode()).isEqualTo(204);
    }
}
