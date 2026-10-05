package net.java21.data2flow.ai.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import net.java21.data2flow.ai.usage.UsageRepository;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 도구 등록(AIA-08.01·02). 도구 정의를 MCP SDK 도구 명세로 바꾸고, 호출마다 토큰 소유자로 권한·토큰 범위를 판정한다.
 *
 * <ul>
 *   <li>범위 밖(토큰 범위가 그 권한을 열지 않음) 또는 역할 권한 없음 → 도구 오류 "권한이 없습니다"</li>
 *   <li>권한 밖 공간·없는 대상 → 도구 오류 "접근 권한 없음"(존재를 드러내지 않음)</li>
 *   <li>입력 형식 오류 → JSON-RPC -32602</li>
 * </ul>
 * 호출마다 사용량(usage_logs, 기능 MCP, 모델 칸에 도구 이름)을 남긴다(AIA-08.04).
 */
public class McpToolRegistry {

    public static final String DENIED = "권한이 없습니다(토큰 범위 또는 역할)";
    public static final String NOT_VISIBLE = "접근 권한 없음: 대상이 없거나 볼 수 있는 범위 밖입니다";
    public static final String DISABLED = "AI 기능이 꺼져 있습니다(조직 설정)";
    public static final String UNAVAILABLE = "일시적으로 조회할 수 없습니다. 잠시 후 다시 시도하세요";
    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, McpToolDefinition> tools = new LinkedHashMap<>();
    private final RoleChecker roleChecker;
    private final UsageRepository usage;
    private final Clock clock;
    private final java.util.function.LongPredicate aiEnabled;

    /**
     * @param aiEnabled 조직 AI 기능이 켜져 있는가. 꺼진 조직의 도구 호출은 도구 오류 "AI 기능이 꺼져 있습니다"
     */
    public McpToolRegistry(List<McpToolDefinition> definitions, RoleChecker roleChecker, UsageRepository usage, Clock clock,
                           java.util.function.LongPredicate aiEnabled) {
        definitions.forEach(d -> tools.put(d.name(), d));
        this.roleChecker = roleChecker;
        this.usage = usage;
        this.clock = clock;
        this.aiEnabled = aiEnabled;
    }

    public Collection<McpToolDefinition> all() {
        return tools.values();
    }

    public Optional<McpToolDefinition> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 이 범위 목록으로 보이는 도구(BR-AIA-12) */
    public List<McpToolDefinition> visibleFor(Collection<String> scopes) {
        return tools.values().stream().filter(t -> scopes.contains(t.scope().code())).toList();
    }

    public List<McpStatelessServerFeatures.SyncToolSpecification> specifications() {
        return tools.values().stream().map(this::spec).toList();
    }

    private McpStatelessServerFeatures.SyncToolSpecification spec(McpToolDefinition d) {
        McpSchema.Tool tool = McpSchema.Tool.builder().name(d.name()).title(d.title()).description(d.description())
                .inputSchema(d.inputSchema())
                .annotations(new McpSchema.ToolAnnotations(d.title(), true, false, true, false, null))
                .meta(Map.of("version", d.version(), "scope", d.scope().code()))
                .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((context, request) -> call(d, McpCaller.user(context), request.arguments() == null ? Map.of() : request.arguments()))
                .build();
    }

    McpSchema.CallToolResult call(McpToolDefinition d, CurrentUser user, Map<String, Object> args) {
        String status = "OK";
        if (!aiEnabled.test(user.organizationId())) {
            return McpSchema.CallToolResult.builder().addTextContent(DISABLED).isError(true).build();
        }
        try {
            JsonNode out = CurrentUserHolder.callAs(user, () -> {
                roleChecker.require(d.permission());
                return d.handler().apply(user, args);
            });
            String text = JSON.writeValueAsString(out);
            return McpSchema.CallToolResult.builder().addTextContent(text).structuredContent(JSON.convertValue(out, Map.class)).isError(false)
                    .build();
        } catch (McpError e) {
            status = "REFUSED";
            throw e;
        } catch (BusinessException e) {
            status = "REFUSED";
            String message = e.getErrorCode() == CommonErrorCode.PERMISSION_DENIED ? DENIED
                    : e.getErrorCode() == CommonErrorCode.SERVICE_UNAVAILABLE ? UNAVAILABLE
                    : e.getErrorCode() == CommonErrorCode.INVALID_REQUEST ? "입력값을 확인하세요" : NOT_VISIBLE;
            return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
        } finally {
            record(user, d.name(), status);
        }
    }

    private void record(CurrentUser user, String tool, String status) {
        try {
            usage.insertUsage(new UsageRepository.UsageRecord(user.organizationId(), clock.instant(), user.userId(), "MCP", "MCP", tool, 0, 0,
                    null, status));
        } catch (RuntimeException e) {
            log.warn("MCP 사용량 기록 실패", e);
        }
    }
}
