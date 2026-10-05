package net.java21.data2flow.ai.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import net.java21.data2flow.ai.common.CoreClient;
import net.java21.data2flow.ai.usage.UsageRepository;
import net.java21.data2flow.ai.support.MutableClock;
import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.BuiltinRole;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AIA-08.02 MCP 읽기 도구 모양(core는 흉내) */
class McpToolsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    final CoreClient core = mock(CoreClient.class);
    final McpTools tools = new McpTools(core, 2);
    final CurrentUser user = new CurrentUser(1, 1, 5L, Set.of("read:devices", "read:telemetry", "read:analytics"));

    JsonNode run(String name, Map<String, Object> args) {
        return tools.definitions().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow().handler().apply(user, args);
    }

    static JsonNode body(String response) {
        return JSON.readTree("{\"header\":{\"isSuccessful\":true},\"response\":" + response + "}");
    }

    @Test
    @DisplayName("[AIA-08.02] aggregate_telemetry: 공간(space-series) 또는 기기(series+agg) 중 하나만, 요약 포함")
    void aggregate() {
        when(core.getAsUser(any(), any(), any())).thenReturn(body("{\"spaceId\":\"3\",\"metric\":\"co2\",\"unit\":\"ppm\",\"points\":[[\"t1\",800,2],[\"t2\",1200,2],[\"t3\",1000,2]]}"));
        JsonNode r = run("aggregate_telemetry", Map.of("spaceId", "3", "metric", "co2", "fn", "max", "from", "2026-10-01T00:00:00Z", "to", "2026-10-02T00:00:00Z"));
        assertThat(r.path("rows").size()).isEqualTo(2);
        assertThat(r.path("summary").path("max").asDouble()).isEqualTo(1200);
        assertThat(r.path("nextCursor").asString()).isNotBlank();
        assertThatThrownBy(() -> run("aggregate_telemetry", Map.of("metric", "co2", "from", "2026-10-01T00:00:00Z", "to", "2026-10-02T00:00:00Z")))
                .isInstanceOf(McpError.class);
        assertThatThrownBy(() -> run("aggregate_telemetry", Map.of("deviceId", "1", "metric", "co2", "from", "어제", "to", "2026-10-02T00:00:00Z")))
                .isInstanceOf(McpError.class);
        when(core.getAsUser(any(), any(), any())).thenReturn(body("{\"series\":[{\"deviceId\":\"1\",\"metric\":\"co2\",\"points\":[[\"t\",5,0]]}]}"));
        assertThat(run("aggregate_telemetry", Map.of("deviceId", "1", "metric", "co2", "from", "2026-10-01T00:00:00Z", "to", "2026-10-02T00:00:00Z"))
                .path("rows").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("[AIA-08.02] 목록·트리·결과·플로우 상태 모양, 표는 최대 행으로 자르고 차트 점은 뺀다, 커서 형식 오류는 -32602")
    void shapes() {
        when(core.getAsUser(any(), any(), any())).thenReturn(body("[{\"id\":\"1\"},{\"id\":\"2\"},{\"id\":\"3\"}]"));
        assertThat(run("list_spaces", Map.of()).path("rows").size()).isEqualTo(2);

        JsonNode list = JSON.readTree("{\"header\":{},\"page\":1,\"totalPages\":1,\"totalCount\":3,\"responses\":[{\"id\":1},{\"id\":2},{\"id\":3}]}");
        when(core.getAsUser(any(), any(), any())).thenReturn(list);
        JsonNode devices = run("list_devices", Map.of("q", "센서"));
        assertThat(devices.path("rows").size()).isEqualTo(2);
        assertThat(devices.path("totalCount").asInt()).isEqualTo(3);

        when(core.getAsUser(any(), any(), any())).thenReturn(body("""
                {"run":{"status":"SUCCEEDED"},"result":{"summary":{"headline":"h"},"tables":[{"id":"t","rows":[1,2,3]}],
                 "charts":[{"id":"c","type":"line","series":[{"data":[[1,2]]}]}]}}
                """));
        JsonNode result = run("get_analysis_result", Map.of("analysisId", "1", "runId", "2"));
        assertThat(result.path("result").path("tables").get(0).path("rows").size()).isEqualTo(2);
        assertThat(result.path("result").path("tables").get(0).path("totalRows").asInt()).isEqualTo(3);
        assertThat(result.path("result").path("charts").get(0).has("series")).isFalse();

        when(core.getAsUser(any(), any(), any())).thenReturn(body("{\"flowId\":\"ab-1\",\"name\":\"환기\",\"status\":\"ACTIVE\",\"definition\":{}}"));
        JsonNode flow = run("get_flow_status", Map.of("flowId", "ab-1"));
        assertThat(flow.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(flow.has("definition")).isFalse();
        assertThatThrownBy(() -> run("get_flow_status", Map.of("flowId", "../x"))).isInstanceOf(McpError.class);
        assertThatThrownBy(() -> McpTools.cursor(Map.of("cursor", "!!!"))).isInstanceOf(McpError.class);
        assertThatThrownBy(() -> McpTools.cursor(Map.of("cursor", java.util.Base64.getUrlEncoder().encodeToString("x=1".getBytes()))))
                .isInstanceOf(McpError.class);
        assertThatThrownBy(() -> McpTools.id(Map.of(), "spaceId")).isInstanceOf(McpError.class);
        assertThatThrownBy(() -> McpTools.str(Map.of("q", " "), "q")).isInstanceOf(McpError.class);
    }

    @Test
    @DisplayName("[AIA-08.01] 등록: 호출자 신원으로 판정, AI 꺼진 조직·core 장애·입력 오류는 도구 오류 문구, 사용량 기록 실패는 무시")
    void registry() {
        RoleChecker checker = new RoleChecker((org, u) -> AccessGrant.of(BuiltinRole.VIEWER, SpaceScope.all()), null);
        UsageRepository usage = mock(UsageRepository.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(usage).insertUsage(any());
        Map<Long, Boolean> enabled = new HashMap<>(Map.of(1L, true, 2L, false));
        McpToolRegistry registry = new McpToolRegistry(tools.definitions(), checker, usage, new MutableClock(Instant.EPOCH), enabled::get);
        assertThat(registry.specifications()).hasSize(11);
        assertThat(registry.visibleFor(Set.of("read:telemetry"))).extracting(McpToolDefinition::name)
                .containsExactlyInAnyOrder("query_telemetry", "aggregate_telemetry", "list_alarms");
        assertThat(registry.find("get_device")).isPresent();

        McpToolDefinition getDevice = registry.find("get_device").orElseThrow();
        McpSchema.CallToolResult off = registry.call(getDevice, new CurrentUser(9, 2, 5L, Set.of("read:devices")), Map.of("deviceId", "1"));
        assertThat(off.isError()).isTrue();
        assertThat(off.content().toString()).contains(McpToolRegistry.DISABLED);

        when(core.getAsUser(any(), any(), any())).thenThrow(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE));
        McpSchema.CallToolResult down = registry.call(getDevice, user, Map.of("deviceId", "1"));
        assertThat(down.content().toString()).contains(McpToolRegistry.UNAVAILABLE);

        org.mockito.Mockito.reset(core);
        when(core.getAsUser(any(), any(), any())).thenThrow(new BusinessException(CommonErrorCode.INVALID_REQUEST));
        assertThat(registry.call(getDevice, user, Map.of("deviceId", "1")).content().toString()).contains("입력값");

        McpTransportContext ctx = McpTransportContext.create(Map.of("X-USER-ID", "1", "X-ORG-ID", "1", "X-ACCESS-TOKEN-ID", "5",
                "X-TOKEN-SCOPE", "read:devices"));
        assertThat(McpCaller.user(ctx).scopes()).containsExactly("read:devices");
        assertThat(McpCaller.str(ctx, "없음")).isNull();
        assertThat(List.of(McpTools.VERSION)).containsExactly("v1");
    }
}
