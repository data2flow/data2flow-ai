package net.java21.data2flow.ai.support;

import net.java21.data2flow.contracts.authz.BuiltinRole;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 통합 시험 바탕: Testcontainers PostgreSQL 18 + pgvector, Redis(Valkey 대신 같은 프로토콜의 redis:7.2), core·analytics·pipeline 흉내
 * (MockWebServer), 가짜 LLM(FakeChatModel). 실제 s3·s4·외부 LLM에는 붙지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"management.server.port=0"})
@Import(AbstractIT.TestBeans.class)
public abstract class AbstractIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres")).withInitScript("init-vector.sql");
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
    public static final FakeChatModel FAKE = new FakeChatModel();
    static final Map<String, String> GRANTS = new ConcurrentHashMap<>();
    private static final Pattern GRANT_PATH = Pattern.compile("/internal/core/organizations/(\\d+)/users/(\\d+)/access-grant");

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        FakeChatModel fakeChatModel() {
            return FAKE;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("data2flow.ai.flyway-mode", () -> "migrate");
        r.add("data2flow.ai.core-uri", Downstream::url);
        r.add("data2flow.ai.analytics-uri", Downstream::url);
        r.add("data2flow.ai.pipeline-uri", Downstream::url);
        r.add("data2flow.ai.llm.default-provider", () -> "FAKE");
        r.add("data2flow.ai.llm.allowed-providers", () -> "NONE,FAKE,ANTHROPIC");
        r.add("data2flow.ai.llm.timeout", () -> "2s");
        r.add("data2flow.ai.web-base-url", () -> "https://data2flow.test");
    }

    @LocalServerPort
    protected int port;
    @Autowired
    protected JdbcTemplate jdbc;
    protected final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetWorld() {
        FAKE.reset();
        Downstream.reset();
        GRANTS.clear();
        Downstream.on("GET /internal/core/organizations/", req -> {
            Matcher m = GRANT_PATH.matcher(req.getPath());
            if (!m.find()) {
                return new MockResponse().setResponseCode(404);
            }
            String grant = GRANTS.get(m.group(1) + ":" + m.group(2));
            if (grant == null) {
                return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                        .setBody("{\"header\":{\"isSuccessful\":true},\"response\":{\"active\":false}}");
            }
            return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(grant);
        });
        Downstream.json("POST /internal/core/audit-logs", 202, "{}");
        jdbc.update("DELETE FROM data2flow_ai.ai_settings");
        jdbc.update("DELETE FROM data2flow_ai.commentaries");
        jdbc.update("DELETE FROM data2flow_ai.script_assists");
        jdbc.update("DELETE FROM data2flow_ai.conversations");
        jdbc.update("DELETE FROM data2flow_ai.usage_logs");
        jdbc.update("DELETE FROM data2flow_ai.prompt_logs");
        jdbc.update("DELETE FROM data2flow_ai.eval_runs");
    }

    /** 사용자 권한(core 권한 판정 흉내). spaceIds가 비면 전체 공간 */
    protected static void grant(long org, long user, BuiltinRole role, Long... spaceIds) {
        String perms = role.permissions().stream().map(p -> "\"" + p.name() + "\"").collect(Collectors.joining(","));
        String scope = spaceIds.length == 0 ? "{\"unrestricted\":true}"
                : "{\"unrestricted\":false,\"allowedSpaceIds\":[" + Arrays.stream(spaceIds).map(s -> "\"" + s + "\"").collect(Collectors.joining(","))
                + "]}";
        GRANTS.put(org + ":" + user, "{\"header\":{\"isSuccessful\":true},\"response\":{\"active\":true,\"role\":\"" + role.name()
                + "\",\"permissions\":[" + perms + "],\"spaceScope\":" + scope + "}}");
    }

    protected HttpRequest.Builder request(String path, long org, long user) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("X-USER-ID", Long.toString(user))
                .header("X-ORG-ID", Long.toString(org)).header("Accept-Language", "ko");
    }

    protected HttpResponse<String> get(String path, long org, long user) throws Exception {
        return http.send(request(path, org, user).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> post(String path, long org, long user, String json) throws Exception {
        return http.send(request(path, org, user).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> put(String path, long org, long user, String json) throws Exception {
        return http.send(request(path, org, user).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> delete(String path, long org, long user) throws Exception {
        return http.send(request(path, org, user).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }
}
