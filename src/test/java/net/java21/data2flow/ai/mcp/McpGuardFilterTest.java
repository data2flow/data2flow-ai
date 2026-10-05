package net.java21.data2flow.ai.mcp;

import net.java21.data2flow.ai.usage.InMemoryCounterStore;
import net.java21.data2flow.ai.support.MutableClock;
import net.java21.data2flow.contracts.web.ErrorMessages;
import net.java21.data2flow.contracts.web.ServletErrorWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** AIA-08.04 MCP 호출 한도(BR-AIA-11), AIA-08.03 범위별 도구 목록(BR-AIA-12) — TC-AIA-084 */
class McpGuardFilterTest {

    static ServletErrorWriter errors() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        return new ServletErrorWriter(new ErrorMessages(messages), JsonMapper.builder().build());
    }

    static MockHttpServletRequest request(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("X-ACCESS-TOKEN-ID", token);
        req.addHeader("X-TOKEN-SCOPE", "read:devices");
        req.setContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}".getBytes(StandardCharsets.UTF_8));
        return req;
    }

    @Test
    @DisplayName("[AIA-08.04][AT-AIA-08.3][TC-AIA-084] 토큰별 분당 60회: 61번째 429 MCP_RATE_LIMITED, Retry-After = 창 끝까지 남은 초, 다음 분 초기화")
    void rateLimit() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-05T00:00:45Z"));
        McpGuardFilter filter = new McpGuardFilter(new InMemoryCounterStore(clock), errors(), 60, clock);
        for (int i = 0; i < 60; i++) {
            MockHttpServletResponse ok = new MockHttpServletResponse();
            filter.doFilter(request("5"), ok, new MockFilterChain());
            assertThat(ok.getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = new MockHttpServletResponse();
        filter.doFilter(request("5"), limited, new MockFilterChain());
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("15");
        assertThat(limited.getContentAsString(StandardCharsets.UTF_8)).contains("MCP_RATE_LIMITED").contains("15초");

        MockHttpServletResponse other = new MockHttpServletResponse();
        filter.doFilter(request("6"), other, new MockFilterChain());
        assertThat(other.getStatus()).isEqualTo(200);

        clock.advance(Duration.ofSeconds(15));
        MockHttpServletResponse next = new MockHttpServletResponse();
        filter.doFilter(request("5"), next, new MockFilterChain());
        assertThat(next.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("[AIA-08.01][TC-AIA-075] 장기 토큰 헤더가 없으면 401, /mcp 밖은 거르지 않는다, GET은 통과")
    void tokenRequiredAndScope() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-05T00:00:00Z"));
        McpGuardFilter filter = new McpGuardFilter(new InMemoryCounterStore(clock), errors(), 60, clock);
        MockHttpServletRequest noToken = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(noToken, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).contains("invalid_token");

        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/ai/settings");
        MockHttpServletResponse otherRes = new MockHttpServletResponse();
        filter.doFilter(other, otherRes, new MockFilterChain());
        assertThat(otherRes.getStatus()).isEqualTo(200);

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/mcp");
        get.addHeader("X-ACCESS-TOKEN-ID", "9");
        MockHttpServletResponse getRes = new MockHttpServletResponse();
        filter.doFilter(get, getRes, new MockFilterChain());
        assertThat(getRes.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("[AIA-08.03][BR-AIA-12] tools/list 응답에서 토큰 범위에 없는 도구를 뺀다, JSON이 아니면 그대로")
    void filterTools() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"a\",\"_meta\":{\"scope\":\"read:devices\"}},"
                + "{\"name\":\"b\",\"_meta\":{\"scope\":\"mcp:write\"}},{\"name\":\"c\"}]}}";
        String out = new String(McpGuardFilter.filterTools(body.getBytes(StandardCharsets.UTF_8), Set.of("read:devices")), StandardCharsets.UTF_8);
        assertThat(out).contains("\"a\"").doesNotContain("\"b\"").doesNotContain("\"c\"");
        assertThat(McpGuardFilter.filterTools("x".getBytes(), Set.of())).isEqualTo("x".getBytes());
        assertThat(McpGuardFilter.filterTools("{\"error\":{}}".getBytes(), Set.of())).isEqualTo("{\"error\":{}}".getBytes());
        assertThat(McpGuardFilter.method("not json".getBytes())).isEmpty();
    }
}
