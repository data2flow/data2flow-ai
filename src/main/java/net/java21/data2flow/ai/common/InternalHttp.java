package net.java21.data2flow.ai.common;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.ErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * 내부 HTTP 호출(ADR-021: {@code http://data2flow-<svc>}, 토큰 없음, {@code X-CALLER-SERVICE: data2flow-ai}).
 *
 * <p>사용자를 대신한 호출(BR-AIA-05)은 요청 사용자의 신원 헤더({@code X-USER-ID}·{@code X-ORG-ID}, 장기 토큰이면
 * {@code X-ACCESS-TOKEN-ID}·{@code X-TOKEN-SCOPE})를 그대로 넘긴다. 그러면 core가 gateway 경유 요청과 같은 RoleChecker·공간 범위·
 * 토큰 범위(ApiScope)로 판정한다. 서비스 계정 권한으로 사용자 데이터를 읽지 않는다.
 *
 * <p>응답 상태는 이렇게 바꾼다: 404 → 넘긴 도메인 코드(존재를 숨김), 403 → PERMISSION_DENIED, 400 → INVALID_REQUEST,
 * 그 밖의 실패·연결 실패 → SERVICE_UNAVAILABLE(503).
 */
public class InternalHttp {

    public static final String CALLER = "data2flow-ai";
    private static final Logger log = LoggerFactory.getLogger(InternalHttp.class);

    private final RestClient client;

    public InternalHttp(String baseUrl, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** GET. 404면 {@code notFound}, 응답 본문(공통 봉투 전체)을 돌려준다 */
    public JsonNode get(CurrentUser user, Function<UriBuilder, URI> uri, ErrorCode notFound) {
        return exchange(user, notFound, client.get().uri(uri));
    }

    /** 조직 단위 GET(사용자 없이 조직만 넘김, 자동 해설처럼 요청 밖에서 쓰는 조회) */
    public JsonNode getForOrganization(long organizationId, Function<UriBuilder, URI> uri, ErrorCode notFound) {
        RestClient.RequestHeadersSpec<?> spec = client.get().uri(uri);
        spec.header(DataflowHeaders.ORG_ID, Long.toString(organizationId));
        return exchange(null, notFound, spec);
    }

    /** POST(JSON). 404면 {@code notFound} */
    public JsonNode post(CurrentUser user, String path, Object body, ErrorCode notFound) {
        RestClient.RequestBodySpec spec = client.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            spec.body(body);
        }
        return exchange(user, notFound, spec);
    }

    /** 응답을 기다리지 않아도 되는 POST(감사 기록 등). 실패는 예외로 알린다 */
    public void postNoContent(Map<String, String> headers, String path, Object body) {
        RestClient.RequestBodySpec spec = client.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        spec.header(DataflowHeaders.CALLER_SERVICE, CALLER);
        headers.forEach(spec::header);
        spec.body(body).retrieve().toBodilessEntity();
    }

    private JsonNode exchange(CurrentUser user, ErrorCode notFound, RestClient.RequestHeadersSpec<?> spec) {
        spec.headers(h -> identity(h, user));
        try {
            return spec.exchange((req, res) -> {
                int status = res.getStatusCode().value();
                if (status == 404) {
                    throw new BusinessException(notFound);
                }
                if (status == 403) {
                    throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
                }
                if (status == 400) {
                    throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
                }
                if (status >= 300) {
                    log.warn("내부 호출 실패 {} {}", req.getURI().getPath(), status);
                    throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
                }
                return status == 204 ? null : res.bodyTo(JsonNode.class);
            });
        } catch (ResourceAccessException e) {
            log.warn("내부 호출 연결 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** 신원 헤더(사용자 위임) + 호출자·요청 ID·언어 */
    public static void identity(HttpHeaders h, CurrentUser user) {
        h.set(DataflowHeaders.CALLER_SERVICE, CALLER);
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            h.set(DataflowHeaders.REQUEST_ID, requestId);
        }
        h.set(HttpHeaders.ACCEPT_LANGUAGE, LocaleContextHolder.getLocale().getLanguage());
        if (user == null) {
            return;
        }
        h.set(DataflowHeaders.USER_ID, Long.toString(user.userId()));
        h.set(DataflowHeaders.ORG_ID, Long.toString(user.organizationId()));
        if (user.viaAccessToken()) {
            h.set(DataflowHeaders.ACCESS_TOKEN_ID, Long.toString(user.accessTokenId()));
            h.set(DataflowHeaders.TOKEN_SCOPE, String.join(" ", user.scopes().stream().sorted().toList()));
        }
    }
}
