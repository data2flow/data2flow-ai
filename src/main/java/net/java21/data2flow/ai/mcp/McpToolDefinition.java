package net.java21.data2flow.ai.mcp;

import net.java21.data2flow.contracts.authz.ApiScope;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.identity.CurrentUser;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * MCP 도구 정의(코드 상수, 버전 관리, spec/detail/AIA/domain-model.md "MCP 도구 정의").
 *
 * @param name        도구 이름(AIA-api.md §3 표)
 * @param version     도구 버전(바뀌면 올리고 docs/mcp-tools-changelog.md에 적는다, AIA-08.05)
 * @param title       표시 이름
 * @param description 설명
 * @param scope       토큰 범위(이 범위가 없는 토큰에는 목록에 보이지 않는다)
 * @param permission  사용자 권한(토큰 범위와 함께 RoleChecker가 판정)
 * @param inputSchema JSON Schema
 * @param handler     (신원, 인자) → 결과 JSON
 */
public record McpToolDefinition(String name, String version, String title, String description, ApiScope scope, Permission permission,
                                Map<String, Object> inputSchema, BiFunction<CurrentUser, Map<String, Object>, JsonNode> handler) {
}
