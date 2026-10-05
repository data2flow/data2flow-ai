package net.java21.data2flow.ai.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import net.java21.data2flow.ai.common.AiErrorCode;
import net.java21.data2flow.ai.usage.CounterStore;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.contracts.web.ServletErrorWriter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * MCP 입구 필터({@code /mcp}).
 *
 * <ol>
 *   <li>장기 토큰 요청만 받는다: gateway가 넣는 {@code X-ACCESS-TOKEN-ID}가 없으면 401 {@code AUTH_TOKEN_INVALID}(AIA-08.01).
 *       신원 헤더 자체가 없으면 앞의 신원 필터가 이미 401을 준다.</li>
 *   <li>토큰당 분당 호출 한도(기본 60, BR-AIA-11): 넘으면 429 {@code MCP_RATE_LIMITED} + {@code Retry-After}(창 끝까지 남은 초).</li>
 *   <li>{@code tools/list} 응답에서 토큰 범위가 없는 도구를 뺀다(BR-AIA-12).</li>
 * </ol>
 */
public class McpGuardFilter extends OncePerRequestFilter {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final CounterStore counters;
    private final ServletErrorWriter errors;
    private final int perMinute;
    private final Clock clock;

    public McpGuardFilter(CounterStore counters, ServletErrorWriter errors, int perMinute, Clock clock) {
        this.counters = counters;
        this.errors = errors;
        this.perMinute = perMinute;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.equals("/mcp") || uri.startsWith("/mcp/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tokenId = request.getHeader(DataflowHeaders.ACCESS_TOKEN_ID);
        if (tokenId == null || !tokenId.matches("\\d{1,19}")) {
            errors.write(request, response, CommonErrorCode.AUTH_TOKEN_INVALID, Map.of("WWW-Authenticate", "Bearer error=\"invalid_token\""));
            return;
        }
        Instant now = clock.instant();
        long minute = now.getEpochSecond() / 60;
        Instant windowEnd = Instant.ofEpochSecond((minute + 1) * 60);
        int over = counters.acquire(List.of("data2flow:ai:mcp:rl:" + tokenId + ":" + minute), List.of((long) perMinute), List.of(), List.of(),
                windowEnd.plusSeconds(60));
        if (over >= 0) {
            long retryAfter = Math.max(1, windowEnd.getEpochSecond() - now.getEpochSecond());
            errors.write(request, response, AiErrorCode.MCP_RATE_LIMITED,
                    Map.of(DataflowHeaders.RETRY_AFTER, Long.toString(retryAfter), "X-RateLimit-Limit", Integer.toString(perMinute),
                            "X-RateLimit-Remaining", "0", "X-RateLimit-Reset", Long.toString(retryAfter)),
                    retryAfter);
            return;
        }
        if (!"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        CachedBodyRequest wrapped = new CachedBodyRequest(request, body);
        if (!"tools/list".equals(method(body))) {
            chain.doFilter(wrapped, response);
            return;
        }
        ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(response);
        chain.doFilter(wrapped, cached);
        byte[] filtered = filterTools(cached.getContentAsByteArray(),
                CurrentUser.fromHeaders("0", "0", tokenId, request.getHeader(DataflowHeaders.TOKEN_SCOPE)).scopes());
        cached.resetBuffer();
        response.setContentLength(filtered.length);
        response.getOutputStream().write(filtered);
        response.flushBuffer();
    }

    static String method(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            return node.path("method").asString("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** {@code result.tools[]}에서 {@code _meta.scope}가 토큰 범위에 없는 도구를 뺀다 */
    static byte[] filterTools(byte[] json, java.util.Set<String> scopes) {
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode tools = root.path("result").path("tools");
            if (tools instanceof ArrayNode arr) {
                ArrayNode kept = JSON.createArrayNode();
                for (JsonNode t : arr) {
                    String scope = t.path("_meta").path("scope").asString("");
                    if (scopes.contains(scope)) {
                        kept.add(t);
                    }
                }
                ((ObjectNode) root.path("result")).set("tools", kept);
                return JSON.writeValueAsBytes(root);
            }
            return json;
        } catch (RuntimeException e) {
            return json;
        }
    }

    /** 본문을 한 번 읽은 뒤 다시 읽게 해 주는 요청 */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
