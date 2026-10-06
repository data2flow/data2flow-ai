package net.java21.data2flow.ai.common;

import net.java21.data2flow.contracts.audit.AuditEvent;
import net.java21.data2flow.contracts.audit.AuditRecorder;
import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.ErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * core-api 호출.
 *
 * <ul>
 *   <li>{@code GET /internal/core/organizations/{organization-id}/users/{user-id}/access-grant}: 권한 판정(IAM-04.01, ACT-api 표와 같은 계약)</li>
 *   <li>API-IAM-39 {@code POST /internal/core/audit-logs}: 감사 기록(202, 비동기 — 감사 실패가 업무를 막지 않음)</li>
 *   <li>사용자 위임 조회: core 공개 API를 서비스 경로({@code /core/**}, gateway stripPrefix(2) 뒤와 같은 경로)로 부르고 요청 사용자의
 *       신원 헤더를 그대로 넘긴다(BR-AIA-05). 예: API-ANA-09, API-DEV-01·11·23·139, API-TSD-01·02·04, API-RUL-10, API-FLW-01·02</li>
 * </ul>
 */
public class CoreClient implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(CoreClient.class);

    private final InternalHttp http;
    private final Executor auditExecutor;

    public CoreClient(InternalHttp http, Executor auditExecutor) {
        this.http = http;
        this.auditExecutor = auditExecutor;
    }

    /** 웹 신원의 권한(없는 사용자·비활성·다른 조직이면 권한 없음). core에 닿지 못하면 503(fail-closed) */
    public AccessGrant accessGrant(long organizationId, long userId) {
        return accessGrant(organizationId, userId, null);
    }

    /**
     * 신원의 권한. 장기 토큰(MCP·API 키) 주체면 {@code ?accessTokenId=}로 묻는다 — core가 소유자 권한 ∩ 토큰 범위·공간(서비스 계정은 범위 권한)으로
     * 판정한다(IAM-05.01·IAM-04.07). 웹 신원이면 {@code accessTokenId}는 null
     */
    public AccessGrant accessGrant(long organizationId, long userId, Long accessTokenId) {
        JsonNode body;
        try {
            body = http.get(null, b -> {
                b.path("/internal/core/organizations/{org}/users/{user}/access-grant");
                if (accessTokenId != null) {
                    b.queryParam("accessTokenId", accessTokenId);
                }
                return b.build(organizationId, userId);
            }, CommonErrorCode.RESOURCE_NOT_FOUND);
        } catch (BusinessException e) {
            if (e.getErrorCode() == CommonErrorCode.RESOURCE_NOT_FOUND) {
                return AccessGrant.none();
            }
            throw e;
        }
        JsonNode r = body == null ? null : body.path("response");
        if (r == null || !r.path("active").asBoolean(false)) {
            return AccessGrant.none();
        }
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        r.path("permissions").forEach(p -> {
            try {
                permissions.add(Permission.valueOf(p.asString()));
            } catch (IllegalArgumentException ignored) {
                // 이 코드보다 새 권한 이름은 무시(기본 거부)
            }
        });
        JsonNode scope = r.path("spaceScope");
        SpaceScope spaceScope;
        if (scope.path("unrestricted").asBoolean(true)) {
            spaceScope = SpaceScope.all();
        } else {
            Set<Long> ids = new LinkedHashSet<>();
            scope.path("allowedSpaceIds").forEach(n -> ids.add(Long.parseLong(n.asString())));
            spaceScope = SpaceScope.only(ids);
        }
        return new AccessGrant(r.path("role").asString("CUSTOM"), permissions, spaceScope);
    }

    /** 사용자 위임 GET. 응답 본문(공통 봉투)을 돌려준다 */
    public JsonNode getAsUser(CurrentUser user, Function<UriBuilder, URI> uri, ErrorCode notFound) {
        return http.get(user, uri, notFound);
    }

    /** 사용자 위임 POST */
    public JsonNode postAsUser(CurrentUser user, String path, Object body, ErrorCode notFound) {
        return http.post(user, path, body, notFound);
    }

    /** API-IAM-39. 비동기로 보내고 실패는 로그만 남긴다 */
    @Override
    public void record(AuditEvent event) {
        auditExecutor.execute(() -> {
            try {
                // 조직은 본문(organizationId)으로 넘긴다. X-ORG-ID만 있고 X-USER-ID가 없으면 core 신원 필터가 401로 거절해 감사가 사라진다
                http.postNoContent(Map.of(), "/internal/core/audit-logs", event);
            } catch (RuntimeException e) {
                log.warn("감사 기록 전송 실패 action={}", event.action(), e);
            }
        });
    }
}
