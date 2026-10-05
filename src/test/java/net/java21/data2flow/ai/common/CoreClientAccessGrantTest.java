package net.java21.data2flow.ai.common;

import net.java21.data2flow.contracts.authz.CachingPermissionLookup;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.PermissionLookup;
import net.java21.data2flow.ai.support.MutableClock;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** IAM-05.01·IAM-04.07: ai는 장기 토큰(MCP) 요청의 권한을 core access-grant ?accessTokenId=로 묻고, 웹 신원과 다른 캐시 칸에 둔다 */
class CoreClientAccessGrantTest {

    private final MockWebServer core = new MockWebServer();
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() throws IOException {
        core.shutdown();
    }

    @Test
    @DisplayName("[IAM-05.01][IAM-04.07] 같은 사용자 웹 → 토큰 → 웹: 토큰은 ?accessTokenId=로 따로 판정, 좁은 토큰이 웹 ADMIN을 물려받지 않음")
    void tokenAndWebAreSeparate() throws IOException {
        // given: 웹 신원은 ADMIN·전체, 토큰 501은 TELEMETRY 읽기·공간 20만
        core.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                paths.add(request.getPath());
                String body = request.getPath().contains("accessTokenId=501")
                        ? "{\"header\":{\"isSuccessful\":true},\"response\":{\"active\":true,\"role\":\"ADMIN\",\"permissions\":[\"SRC_READ\"],"
                          + "\"spaceScope\":{\"unrestricted\":false,\"allowedSpaceIds\":[\"20\"]}}}"
                        : "{\"header\":{\"isSuccessful\":true},\"response\":{\"active\":true,\"role\":\"ADMIN\",\"permissions\":[\"SRC_READ\",\"DEVICE_CONTROL\"],"
                          + "\"spaceScope\":{\"unrestricted\":true}}}";
                return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
            }
        });
        core.start();
        CoreClient client = new CoreClient(new InternalHttp(core.url("/").toString().replaceAll("/$", ""), Duration.ofSeconds(2)), Runnable::run);
        PermissionLookup lookup = new CachingPermissionLookup(PermissionLookup.tokenAware(client::accessGrant), Duration.ofSeconds(10),
                new MutableClock(Instant.parse("2026-10-06T00:00:00Z")));
        // when & then
        assertThat(lookup.find(1, 7).has(Permission.DEVICE_CONTROL)).isTrue();
        assertThat(lookup.find(1, 7, 501L).has(Permission.DEVICE_CONTROL)).isFalse();
        assertThat(lookup.find(1, 7, 501L).spaceScope().includes(30L)).isFalse();
        assertThat(lookup.find(1, 7, null).spaceScope().unrestricted()).isTrue();
        assertThat(paths).containsExactly("/internal/core/organizations/1/users/7/access-grant",
                "/internal/core/organizations/1/users/7/access-grant?accessTokenId=501");
    }
}
