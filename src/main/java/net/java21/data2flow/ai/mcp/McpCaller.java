package net.java21.data2flow.ai.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 호출자. gateway가 장기 토큰을 확인하고 넣은 헤더(auth.md §8)를 전송 계층이 옮겨 둔 값으로 만든다. 도구는 다른 스레드에서
 * 돌 수 있으므로 신원을 ThreadLocal이 아니라 이 값으로 넘긴다.
 */
public final class McpCaller {

    static final String LANGUAGE = "Accept-Language";

    private McpCaller() {
    }

    /** 전송 계층: 신원 헤더와 요청 ID·언어만 옮긴다(Authorization 등 다른 헤더는 넘기지 않음) */
    public static McpTransportContext extract(ServerRequest request) {
        Map<String, Object> values = new HashMap<>();
        for (String name : new String[]{DataflowHeaders.USER_ID, DataflowHeaders.ORG_ID, DataflowHeaders.ACCESS_TOKEN_ID,
                DataflowHeaders.TOKEN_SCOPE, DataflowHeaders.REQUEST_ID, LANGUAGE}) {
            String value = request.headers().firstHeader(name);
            if (value != null) {
                values.put(name, value);
            }
        }
        return McpTransportContext.create(values);
    }

    /** 신원. 헤더가 없으면(필터에서 이미 401) 예외 */
    public static CurrentUser user(McpTransportContext context) {
        return CurrentUser.fromHeaders(str(context, DataflowHeaders.USER_ID), str(context, DataflowHeaders.ORG_ID),
                str(context, DataflowHeaders.ACCESS_TOKEN_ID), str(context, DataflowHeaders.TOKEN_SCOPE));
    }

    static String str(McpTransportContext context, String key) {
        Object v = context.get(key);
        return v instanceof String s ? s : null;
    }
}
