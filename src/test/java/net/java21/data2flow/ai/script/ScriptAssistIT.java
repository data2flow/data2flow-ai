package net.java21.data2flow.ai.script;

import net.java21.data2flow.ai.safety.PiiMasker;
import net.java21.data2flow.ai.support.AbstractIT;
import net.java21.data2flow.ai.support.Downstream;
import net.java21.data2flow.contracts.authz.BuiltinRole;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** AIA-04.01~03 스크립트 작성 도우미(pipeline API-SCR-30·31 흉내, core 원본 메시지 흉내) */
class ScriptAssistIT extends AbstractIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final long ORG = 4;
    static final long INTEGRATOR = 41;
    static final String REQUEST = """
            {"stage":"DECODE","sample":{"payload":{"id":"dev-1","t":21.5,"h":40,"contact":"ops@corp.example.com"}},
             "requirement":"t는 섭씨 온도, h는 습도로 바꾸고 이슬점을 추가해 줘"}
            """;

    @BeforeEach
    void world() {
        grant(ORG, INTEGRATOR, BuiltinRole.INTEGRATOR);
        Downstream.ok("POST /internal/pipeline/scripts/check", "{\"ok\":true,\"problems\":[]}");
        Downstream.ok("POST /internal/pipeline/scripts/test-run", """
                {"ok":true,"output":{"externalId":"dev-1","metrics":[{"key":"temperature","value":21.5},{"key":"humidity","value":40},
                 {"key":"dew_point","value":7.56}]},"diff":{"added":["temperature","humidity","dew_point"],"removed":[],"changed":[]},
                 "logs":[],"durationMs":3.2,"outputBytes":120}
                """);
    }

    @Test
    @DisplayName("[AIA-04.01][AT-AIA-04.1][TC-AIA-038] 원본 샘플 + '이슬점 추가' → 코드와 시험 결과(출력에 dew_point)가 함께, 정적 검사 → 시험 실행 순서")
    void draftWithTest() throws Exception {
        HttpResponse<String> res = post("/ai/script-assists", ORG, INTEGRATOR, REQUEST);
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode r = JSON.readTree(res.body()).path("response");
        assertThat(r.path("code").asString()).contains("function decode(input, ctx)").contains("dewPoint");
        assertThat(r.path("test").path("status").asString()).isEqualTo("PASS");
        assertThat(r.path("test").path("output").toString()).contains("dew_point");
        assertThat(r.path("attempt").asInt()).isEqualTo(1);
        assertThat(r.path("aiAssisted").asBoolean()).isTrue();

        RecordedRequest check = Downstream.requests("/internal/pipeline/scripts/check").getFirst();
        RecordedRequest run = Downstream.requests("/internal/pipeline/scripts/test-run").getFirst();
        assertThat(check.getSequenceNumber()).isLessThan(run.getSequenceNumber());
        JsonNode runBody = JSON.readTree(run.getBody().readUtf8());
        assertThat(runBody.path("organizationId").asLong()).isEqualTo(ORG);
        assertThat(runBody.path("input").path("payload").path("t").asDouble()).isEqualTo(21.5);
        assertThat(run.getHeader("X-CALLER-SERVICE")).isEqualTo("data2flow-ai");

        // AIA-07.03(TC-AIA-061): 샘플 속 이메일은 LLM 요청에서 가명
        assertThat(PiiMasker.countPii(FAKE.capturedTexts().getFirst())).isZero();
        assertThat(FAKE.capturedTexts().getFirst()).contains("<data id=\"requirement\">");
    }

    @Test
    @DisplayName("[AIA-04.02][TC-AIA-041] 시험 실패 → [고쳐 줘] 재요청에 이전 코드·오류·실패 입력이 데이터 구획으로 가고, 도움 하나에 5회까지")
    void fixLoop() throws Exception {
        Downstream.ok("POST /internal/pipeline/scripts/test-run", """
                {"ok":false,"output":null,"logs":[],"durationMs":1,"outputBytes":0,
                 "error":{"code":"SCRIPT_RUNTIME_ERROR","message":"t is not defined","line":3,"col":5}}
                """);
        JsonNode first = JSON.readTree(post("/ai/script-assists", ORG, INTEGRATOR, REQUEST).body()).path("response");
        assertThat(first.path("test").path("status").asString()).isEqualTo("FAIL");
        String assistId = first.path("assistId").asString();
        String retry = REQUEST.replace("}\n", ",\"previousAttemptId\":\"" + assistId + "\"}\n");
        for (int i = 2; i <= 5; i++) {
            JsonNode next = JSON.readTree(post("/ai/script-assists", ORG, INTEGRATOR, retry).body()).path("response");
            assertThat(next.path("attempt").asInt()).isEqualTo(i);
            assertThat(next.path("assistId").asString()).isEqualTo(assistId);
        }
        assertThat(FAKE.capturedTexts().get(1)).contains("<data id=\"previous\">").contains("t is not defined").contains("function decode");
        HttpResponse<String> sixth = post("/ai/script-assists", ORG, INTEGRATOR, retry);
        assertThat(sixth.statusCode()).isEqualTo(400);
        assertThat(sixth.body()).contains("ATTEMPT_LIMIT");
    }

    @Test
    @DisplayName("[AIA-04.01][TC-AIA-039] 권한: INTEGRATOR·ADMIN 허용, OPERATOR·ANALYST·VIEWER 403, 남의 도움·권한 밖 원본 메시지 404")
    void permissions() throws Exception {
        grant(ORG, 42, BuiltinRole.OPERATOR);
        grant(ORG, 43, BuiltinRole.ANALYST);
        grant(ORG, 44, BuiltinRole.VIEWER);
        grant(ORG, 45, BuiltinRole.ADMIN);
        assertThat(post("/ai/script-assists", ORG, 42, REQUEST).statusCode()).isEqualTo(403);
        assertThat(post("/ai/script-assists", ORG, 43, REQUEST).statusCode()).isEqualTo(403);
        assertThat(post("/ai/script-assists", ORG, 44, REQUEST).statusCode()).isEqualTo(403);
        JsonNode mine = JSON.readTree(post("/ai/script-assists", ORG, 45, REQUEST).body()).path("response");
        String retryOther = REQUEST.replace("}\n", ",\"previousAttemptId\":\"" + mine.path("assistId").asString() + "\"}\n");
        assertThat(post("/ai/script-assists", ORG, INTEGRATOR, retryOther).statusCode()).isEqualTo(404);

        Downstream.on("GET /core/ingest/raw-messages/9", r -> new MockResponse().setResponseCode(404));
        String raw = "{\"stage\":\"DECODE\",\"sample\":{\"rawMessageId\":\"9\"},\"requirement\":\"온도\"}";
        assertThat(post("/ai/script-assists", ORG, INTEGRATOR, raw).statusCode()).isEqualTo(404);
        assertThat(post("/ai/script-assists", ORG, INTEGRATOR, "{\"stage\":\"DECODE\",\"sample\":{},\"requirement\":\"x\"}").statusCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("[AIA-04.01] 원본 메시지 ID 샘플: core에서 요청 사용자로 payload를 읽고, 시험 실행은 rawMessageId로(DECODE)·canonical로(TRANSFORM)")
    void rawMessageSample() throws Exception {
        Downstream.ok("GET /core/ingest/raw-messages/88", """
                {"id":"88","topic":"v/1","payloadEncoding":"JSON","payload":"{\\"t\\":22.1,\\"h\\":55}",
                 "canonical":{"deviceId":"5","metrics":[{"key":"temperature","value":22.1},{"key":"humidity","value":55}]}}
                """);
        String decode = "{\"stage\":\"DECODE\",\"sample\":{\"rawMessageId\":\"88\"},\"requirement\":\"온도 습도\"}";
        assertThat(post("/ai/script-assists", ORG, INTEGRATOR, decode).statusCode()).isEqualTo(200);
        JsonNode runBody = JSON.readTree(Downstream.requests("/internal/pipeline/scripts/test-run").getLast().getBody().readUtf8());
        assertThat(runBody.path("rawMessageId").asLong()).isEqualTo(88);
        assertThat(Downstream.requests("/core/ingest/raw-messages/88").getFirst().getHeader("X-USER-ID")).isEqualTo(Long.toString(INTEGRATOR));
        assertThat(FAKE.capturedTexts().getFirst()).contains("22.1");

        String transform = "{\"stage\":\"TRANSFORM\",\"sample\":{\"rawMessageId\":\"88\"},\"requirement\":\"이슬점 추가\",\"scriptId\":\"3\"}";
        JsonNode t = JSON.readTree(post("/ai/script-assists", ORG, INTEGRATOR, transform).body()).path("response");
        assertThat(t.path("code").asString()).contains("function transform(msg, ctx)");
        JsonNode tBody = JSON.readTree(Downstream.requests("/internal/pipeline/scripts/test-run").getLast().getBody().readUtf8());
        assertThat(tBody.path("input").path("deviceId").asString()).isEqualTo("5");
        assertThat(tBody.path("scriptId").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("[AIA-04.01] 정적 검사 오류면 시험 실행 없이 FAIL과 첫 오류")
    void staticCheckFails() throws Exception {
        Downstream.ok("POST /internal/pipeline/scripts/check",
                "{\"ok\":false,\"problems\":[{\"line\":1,\"col\":1,\"severity\":\"ERROR\",\"code\":\"SCRIPT_FORBIDDEN_API\",\"message\":\"eval\"}]}");
        JsonNode r = JSON.readTree(post("/ai/script-assists", ORG, INTEGRATOR, REQUEST).body()).path("response");
        assertThat(r.path("test").path("status").asString()).isEqualTo("FAIL");
        assertThat(r.path("test").path("error").path("code").asString()).isEqualTo("SCRIPT_FORBIDDEN_API");
        assertThat(Downstream.requests("/internal/pipeline/scripts/test-run")).isEmpty();
    }

    @Test
    @DisplayName("[AIA-04.01][SCR-03.07] 내부 API POST /internal/ai/script-drafts(API-SCR-16 위임): 초안과 설명만, 저장·시험 없음")
    void internalDraft() throws Exception {
        HttpResponse<String> res = post("/internal/ai/script-drafts", ORG, INTEGRATOR,
                "{\"kind\":\"TRANSFORM\",\"requirement\":\"이슬점 추가\",\"samples\":[{\"t\":20}],\"currentCode\":\"function transform(m){return m}\"}");
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode r = JSON.readTree(res.body()).path("response");
        assertThat(r.path("code").asString()).contains("function transform");
        assertThat(r.path("explanation").asString()).isNotBlank();
        assertThat(Downstream.requests("/internal/pipeline")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data2flow_ai.script_assists", Integer.class)).isZero();
        assertThat(FAKE.capturedTexts().getFirst()).contains("<data id=\"current_code\">");

        HttpResponse<String> anonymous = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
                        + "/internal/ai/script-drafts")).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"kind\":\"DECODE\",\"requirement\":\"x\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(400);
    }
}
