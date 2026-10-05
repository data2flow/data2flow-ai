package net.java21.data2flow.ai.mcp;

import net.java21.data2flow.ai.settings.AiSettingsService;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** API-AIA-17 {@code GET /api/v1/ai/mcp/tools}(V 이상): 연결 안내 화면의 도구 이름·버전·범위·설명·입력 스키마(AIA-08.05) */
@RestController
public class McpToolsController {

    private final McpToolRegistry registry;
    private final AiSettingsService settings;
    private final RoleChecker roleChecker;

    public McpToolsController(McpToolRegistry registry, AiSettingsService settings, RoleChecker roleChecker) {
        this.registry = registry;
        this.settings = settings;
        this.roleChecker = roleChecker;
    }

    /** 응답 항목 */
    public record ToolView(String name, String version, String description, String scope, Map<String, Object> inputSchema) {
    }

    @GetMapping("/ai/mcp/tools")
    public ApiResponse<List<ToolView>> tools() {
        settings.requireEnabled(roleChecker.currentUser().organizationId());
        roleChecker.require(Permission.AI_USE);
        return ApiResponse.success(registry.all().stream()
                .map(t -> new ToolView(t.name(), t.version(), t.description(), t.scope().code(), t.inputSchema())).toList());
    }
}
