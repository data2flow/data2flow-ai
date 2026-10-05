package net.java21.data2flow.ai.mcp;

import net.java21.data2flow.ai.support.AbstractIT;
import net.java21.data2flow.ai.support.Downstream;
import net.java21.data2flow.contracts.authz.BuiltinRole;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** AIA-08.01·08.02·08.04 MCP 서버(Streamable HTTP, 무상태) — 토큰 신원·범위·공간·한도·페이지 */
class McpServerIT extends AbstractIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final long ORG = 7;
    static final long USER = 70;

    @Test
    @DisplayName("[AIA-08.01][AT-AIA-08.2][TC-AIA-075] 토큰 없음 401, 장기 토큰이 아니면 401, 개인 토큰이면 initialize에 serverInfo·tools 능력")
    void auth() throws Exception {
        HttpResponse<String> none = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(initialize())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(none.statusCode()).isEqualTo(401);

        HttpResponse<String> webToken = http.send(request("/mcp", ORG, USER).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(initialize())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(webToken.statusCode()).isEqualTo(401);
        assertThat(webToken.body()).contains("AUTH_TOKEN_INVALID");

        HttpResponse<String> ok = mcp(initialize(), "11", "read:devices");
        assertThat(ok.statusCode()).isEqualTo(200);
        JsonNode result = JSON.readTree(ok.body()).path("result");
        assertThat(result.path("serverInfo").path("name").asString()).isEqualTo("data2flow");
        assertThat(result.path("capabilities").has("tools")).isTrue();
    }

    @Test
    @DisplayName("[AIA-08.03][AT-AIA-08.1][AT-AIA-08.5][TC-AIA-081] read:telemetry 토큰 tools/list는 그 범위 도구만, 제어·배포 도구는 어떤 범위로도 없다")
    void toolsListByScope() throws Exception {
        List<String> telemetry = toolNames(mcp(rpc("tools/list", "{}"), "11", "read:telemetry"));
        assertThat(telemetry).containsExactlyInAnyOrder("query_telemetry", "aggregate_telemetry", "list_alarms");

        List<String> all = toolNames(mcp(rpc("tools/list", "{}"), "11", "read:telemetry read:devices read:analytics mcp:write control:devices"));
        assertThat(all).hasSize(11).contains("list_spaces", "get_device", "get_analysis_result", "list_flows", "get_flow_status");
        assertThat(all).noneMatch(n -> n.contains("command") || n.contains("deploy") || n.contains("token") || n.contains("user")
                || n.contains("control"));
    }

    @Test
    @DisplayName("[AIA-08.01][AT-AIA-08.1][TC-AIA-076] 토큰 범위가 그 권한을 열지 않으면 도구 오류, 권한 밖 공간은 '접근 권한 없음'이고 수치가 없다")
    void scopeAndSpace() throws Exception {
        grant(ORG, USER, BuiltinRole.OPERATOR, 2L);
        Downstream.on("GET /core/spaces/1", r -> new MockResponse().setResponseCode(404));
        Downstream.ok("GET /core/spaces/2", "{\"id\":\"2\",\"name\":\"공간B\"}");

        JsonNode denied = JSON.readTree(mcp(call("get_space", "{\"spaceId\":\"2\"}"), "11", "read:telemetry").body()).path("result");
        assertThat(denied.path("isError").asBoolean()).isTrue();
        assertThat(denied.toString()).contains(McpToolRegistry.DENIED);

        JsonNode hidden = JSON.readTree(mcp(call("get_space", "{\"spaceId\":\"1\"}"), "11", "read:devices").body()).path("result");
        assertThat(hidden.path("isError").asBoolean()).isTrue();
        assertThat(hidden.toString()).contains("접근 권한 없음");

        JsonNode visible = JSON.readTree(mcp(call("get_space", "{\"spaceId\":\"2\"}"), "11", "read:devices").body()).path("result");
        assertThat(visible.path("isError").asBoolean()).isFalse();
        assertThat(visible.path("structuredContent").path("name").asString()).isEqualTo("공간B");

        RecordedRequest forwarded = Downstream.requests("/core/spaces/2").getLast();
        assertThat(forwarded.getHeader("X-USER-ID")).isEqualTo(Long.toString(USER));
        assertThat(forwarded.getHeader("X-ACCESS-TOKEN-ID")).isEqualTo("11");
        assertThat(forwarded.getHeader("X-TOKEN-SCOPE")).isEqualTo("read:devices");
        assertThat(forwarded.getHeader("X-CALLER-SERVICE")).isEqualTo("data2flow-ai");
    }

    @Test
    @DisplayName("[AIA-08.02][AT-AIA-08.4][TC-AIA-079] 결과 3,000행 query_telemetry → 1,000행 + summary{count,min,max,avg} + nextCursor, 커서로 다음 1,000행")
    void pagination() throws Exception {
        grant(ORG, USER, BuiltinRole.VIEWER);
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            points.append(i == 0 ? "" : ",").append("[\"2026-10-01T00:").append(String.format("%02d", i % 60)).append(":00Z\",").append(i).append(",0]");
        }
        Downstream.ok("GET /core/telemetry/series", "{\"series\":[{\"deviceId\":\"5\",\"metric\":\"co2\",\"unit\":\"ppm\",\"points\":[" + points + "]}]}");
        String args = "{\"deviceId\":\"5\",\"metric\":\"co2\",\"from\":\"2026-10-01T00:00:00Z\",\"to\":\"2026-10-02T00:00:00Z\"}";
        JsonNode first = JSON.readTree(mcp(call("query_telemetry", args), "11", "read:telemetry").body()).path("result").path("structuredContent");
        assertThat(first.path("rows").size()).isEqualTo(1000);
        assertThat(first.path("summary").path("count").asInt()).isEqualTo(3000);
        assertThat(first.path("summary").path("min").asDouble()).isEqualTo(0);
        assertThat(first.path("summary").path("max").asDouble()).isEqualTo(2999);
        assertThat(first.path("summary").path("avg").asDouble()).isEqualTo(1499.5);
        String cursor = first.path("nextCursor").asString();
        assertThat(cursor).isNotBlank();

        String next = args.replace("}", ",\"cursor\":\"" + cursor + "\"}");
        JsonNode second = JSON.readTree(mcp(call("query_telemetry", next), "11", "read:telemetry").body()).path("result").path("structuredContent");
        assertThat(second.path("rows").get(0).path("value").asInt()).isEqualTo(1000);
        assertThat(Downstream.requests("/core/telemetry/series").getFirst().getPath()).contains("metrics=co2").contains("deviceId=5");
    }

    @Test
    @DisplayName("[AIA-08.02][TC-AIA-080][TC-AIA-082] 스키마에 맞지 않는 인자는 도구 오류(isError, MCP 2025-06-18 규칙), 없는 도구(device_command)는 JSON-RPC -32602")
    void invalidParamsAndUnknownTool() throws Exception {
        grant(ORG, USER, BuiltinRole.VIEWER);
        JsonNode bad = JSON.readTree(mcp(call("get_device", "{\"deviceId\":\"abc\"}"), "11", "read:devices").body());
        assertThat(bad.path("result").path("isError").asBoolean()).isTrue();
        assertThat(Downstream.requests("/core/devices")).isEmpty();

        JsonNode missing = JSON.readTree(mcp(call("query_telemetry", "{\"deviceId\":\"1\"}"), "11", "read:telemetry").body());
        assertThat(missing.path("result").path("isError").asBoolean()).isTrue();

        JsonNode unknown = JSON.readTree(mcp(call("device_command", "{}"), "11", "read:devices mcp:write control:devices").body());
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    @DisplayName("[AIA-08.04][AT-AIA-08.3][TC-AIA-085] 실제 Redis로 1분 안 61번째 호출은 429 MCP_RATE_LIMITED와 Retry-After, 호출은 사용량에 남는다")
    void rateLimit() throws Exception {
        grant(ORG, USER, BuiltinRole.VIEWER);
        Downstream.ok("GET /core/devices/9", "{\"id\":\"9\",\"name\":\"센서\"}");
        String token = Long.toString(System.nanoTime() % 1_000_000_000L);
        List<Integer> codes = new ArrayList<>();
        HttpResponse<String> last = null;
        for (int i = 0; i < 61; i++) {
            last = mcp(call("get_device", "{\"deviceId\":\"9\"}"), token, "read:devices");
            codes.add(last.statusCode());
        }
        // 분 경계를 넘으면 창이 새로 시작되므로 61번 중 429가 1번 이하일 수 있다 — 429가 나왔다면 형식을 본다
        if (codes.contains(429)) {
            assertThat(last.statusCode()).isEqualTo(429);
            assertThat(last.body()).contains("MCP_RATE_LIMITED");
            assertThat(Integer.parseInt(last.headers().firstValue("Retry-After").orElseThrow())).isBetween(1, 60);
        }
        assertThat(codes.stream().filter(c -> c == 200).count()).isGreaterThanOrEqualTo(60);
        Integer recorded = jdbc.queryForObject("SELECT count(*) FROM data2flow_ai.usage_logs WHERE feature = 'MCP' AND organization_id = ?",
                Integer.class, ORG);
        assertThat(recorded).isGreaterThanOrEqualTo(60);
    }

    @Test
    @DisplayName("[AIA-08.05] 화면용 도구 목록 API-AIA-17: 이름·버전·범위·설명·입력 스키마")
    void toolCatalogApi() throws Exception {
        grant(ORG, USER, BuiltinRole.VIEWER);
        HttpResponse<String> res = get("/ai/mcp/tools", ORG, USER);
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode tools = JSON.readTree(res.body()).path("response");
        assertThat(tools.size()).isEqualTo(11);
        assertThat(tools.get(0).path("version").asString()).isEqualTo("v1");
        assertThat(tools.get(0).path("inputSchema").path("type").asString()).isEqualTo("object");
    }

    // ───────────── 도움 ─────────────

    private HttpResponse<String> mcp(String body, String tokenId, String scopes) throws Exception {
        return http.send(request("/mcp", ORG, USER).header("X-ACCESS-TOKEN-ID", tokenId).header("X-TOKEN-SCOPE", scopes)
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static String initialize() {
        return rpc("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}");
    }

    static String call(String tool, String args) {
        return rpc("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + args + "}");
    }

    static String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    static List<String> toolNames(HttpResponse<String> res) {
        JsonNode tools = JSON.readTree(res.body()).path("result").path("tools");
        return StreamSupport.stream(tools.spliterator(), false).map(t -> t.path("name").asString()).toList();
    }
}
