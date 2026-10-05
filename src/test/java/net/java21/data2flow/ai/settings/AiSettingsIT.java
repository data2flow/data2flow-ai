package net.java21.data2flow.ai.settings;

import net.java21.data2flow.ai.support.AbstractIT;
import net.java21.data2flow.ai.support.Downstream;
import net.java21.data2flow.contracts.authz.BuiltinRole;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** AIA-07.05·07.07 AI 설정과 평가 기준(API-AIA-07·16) */
class AiSettingsIT extends AbstractIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final long ORG = 5;
    static final long ADMIN = 51;

    @BeforeEach
    void world() {
        grant(ORG, ADMIN, BuiltinRole.ADMIN);
        grant(ORG, 52, BuiltinRole.OPERATOR);
    }

    static String body(String provider, String model, int baseVersion, boolean enabled) {
        return """
                {"enabled":%s,"provider":"%s","model":"%s","dailyRequestLimit":500,"dailyTokenLimit":1000000,"perUserDailyLimit":50,
                 "logRetentionDays":30,"autoCommentary":true,"evalThreshold":0.9,"suggestionTtlMinutes":30,"baseVersion":%d}
                """.formatted(enabled, provider, model, baseVersion);
    }

    @Test
    @DisplayName("[AIA-07.05][TC-AIA-065] 기본값 조회(FAKE·version 0·제공자 상태), ADMIN만(다른 역할 403), 저장 후 version 1과 감사 AI_SETTINGS_CHANGED")
    void getAndPut() throws Exception {
        JsonNode s = JSON.readTree(get("/ai/settings", ORG, ADMIN).body()).path("response");
        assertThat(s.path("provider").asString()).isEqualTo("FAKE");
        assertThat(s.path("version").asInt()).isZero();
        assertThat(s.path("providers").toString()).contains("\"provider\":\"ANTHROPIC\",\"available\":false")
                .contains("\"provider\":\"OPENAI\",\"available\":false");
        assertThat(get("/ai/settings", ORG, 52).statusCode()).isEqualTo(403);
        assertThat(put("/ai/settings", ORG, 52, body("FAKE", "fake-demo", 0, true)).statusCode()).isEqualTo(403);

        HttpResponse<String> saved = put("/ai/settings", ORG, ADMIN, body("NONE", "none", 0, true));
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(saved.body()).path("response").path("version").asInt()).isEqualTo(1);
        assertThat(JSON.readTree(saved.body()).path("response").path("logRetentionDays").asInt()).isEqualTo(30);
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> Downstream.requests("/internal/core/audit-logs").stream()
                .anyMatch(r -> r.getBody().clone().readUtf8().contains("AI_SETTINGS_CHANGED")));

        HttpResponse<String> stale = put("/ai/settings", ORG, ADMIN, body("NONE", "none", 0, false));
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("VERSION_CONFLICT");
        assertThat(put("/ai/settings", ORG, ADMIN, body("NONE", "none", 1, false)).statusCode()).isEqualTo(200);
        assertThat(get("/ai/settings", ORG, ADMIN).statusCode()).isEqualTo(200);   // AI가 꺼져도 설정 API는 응답
        assertThat(get("/ai/mcp/tools", ORG, ADMIN).statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("[AIA-07.05] 형식 오류 400(보관 기간 7~365, 제안 만료 5~120), 이 배포에서 허용하지 않은 제공자 400")
    void validation() throws Exception {
        assertThat(put("/ai/settings", ORG, ADMIN, body("NONE", "x", 0, true).replace("\"logRetentionDays\":30", "\"logRetentionDays\":3"))
                .statusCode()).isEqualTo(400);
        assertThat(put("/ai/settings", ORG, ADMIN, body("NONE", "x", 0, true).replace("\"suggestionTtlMinutes\":30", "\"suggestionTtlMinutes\":200"))
                .statusCode()).isEqualTo(400);
        HttpResponse<String> notAllowed = put("/ai/settings", ORG, ADMIN, body("GOOGLE", "gemini", 0, true));
        assertThat(notAllowed.statusCode()).isEqualTo(400);
        assertThat(notAllowed.body()).contains("provider");
    }

    @Test
    @DisplayName("[AIA-07.07][AT-AIA-07.3][TC-AIA-072] 평가 이력 없는 모델은 적용 불가, 85% 모델은 409 AI_EVAL_BELOW_THRESHOLD(기준 90%), 90% 이상이면 적용")
    void evalGate() throws Exception {
        HttpResponse<String> noHistory = put("/ai/settings", ORG, ADMIN, body("FAKE", "fake-x", 0, true));
        assertThat(noHistory.statusCode()).isEqualTo(409);
        assertThat(noHistory.body()).contains("AI_EVAL_BELOW_THRESHOLD").contains("90%");

        jdbc.update("INSERT INTO data2flow_ai.eval_sets (organization_id, name) VALUES (0, 'gate') ON CONFLICT DO NOTHING");
        Long set = jdbc.queryForObject("SELECT min(id) FROM data2flow_ai.eval_sets", Long.class);
        jdbc.update("INSERT INTO data2flow_ai.eval_runs (organization_id, eval_set_id, model, prompt_version, accuracy, passed) VALUES (?, ?, 'fake-x', 'v1', 0.85, false)",
                ORG, set);
        assertThat(put("/ai/settings", ORG, ADMIN, body("FAKE", "fake-x", 0, true)).statusCode()).isEqualTo(409);
        jdbc.update("INSERT INTO data2flow_ai.eval_runs (organization_id, eval_set_id, model, prompt_version, accuracy, passed, created_at) VALUES (?, ?, 'fake-x', 'v1', 0.95, true, now() + interval '1 second')",
                ORG, set);
        assertThat(put("/ai/settings", ORG, ADMIN, body("FAKE", "fake-x", 0, true)).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("[AIA-07.07][TC-AIA-073] 평가 실행 API: 사례 목록(해설 50·인젝션 80 이상), FAKE로 실행(202) → 끝나면 정확도·불일치율·방어율이 남고 통과")
    void evalRunApi() throws Exception {
        JsonNode cases = JSON.readTree(get("/ai/evals/cases", ORG, ADMIN).body()).path("response");
        long commentary = java.util.stream.StreamSupport.stream(cases.spliterator(), false).filter(c -> "COMMENTARY".equals(c.path("kind").asString())).count();
        long injection = java.util.stream.StreamSupport.stream(cases.spliterator(), false).filter(c -> c.path("injection").asBoolean()).count();
        assertThat(commentary).isEqualTo(50);
        assertThat(injection).isGreaterThanOrEqualTo(80);

        HttpResponse<String> started = post("/ai/evals/runs", ORG, ADMIN, "{\"model\":\"fake-demo\",\"promptVersion\":\"commentary-v1\",\"provider\":\"FAKE\"}");
        assertThat(started.statusCode()).isEqualTo(202);
        String runId = JSON.readTree(started.body()).path("response").path("runId").asString();
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> jdbc.queryForObject(
                "SELECT accuracy IS NOT NULL FROM data2flow_ai.eval_runs WHERE id = ?", Boolean.class, Long.parseLong(runId)));
        JsonNode runs = JSON.readTree(get("/ai/evals/runs", ORG, ADMIN).body());
        JsonNode run = runs.path("responses").get(0);
        assertThat(run.path("passed").asBoolean()).isTrue();
        assertThat(run.path("numberMatchRate").decimalValue()).isGreaterThanOrEqualTo(new java.math.BigDecimal("0.98"));
        assertThat(run.path("injectionBlockRate").decimalValue()).isEqualByComparingTo("1");

        assertThat(post("/ai/evals/runs", ORG, ADMIN, "{\"model\":\"m\",\"provider\":\"ANTHROPIC\"}").statusCode()).isEqualTo(503);
        assertThat(get("/ai/evals/runs", ORG, 52).statusCode()).isEqualTo(403);
    }
}
