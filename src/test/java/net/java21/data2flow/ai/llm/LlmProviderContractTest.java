package net.java21.data2flow.ai.llm;

import net.java21.data2flow.ai.common.AiErrorCode;
import net.java21.data2flow.ai.common.AiProperties;
import net.java21.data2flow.ai.safety.PromptAssembler;
import net.java21.data2flow.ai.support.MutableClock;
import net.java21.data2flow.contracts.error.BusinessException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LLM 파사드 계약 시험(ADR-040, external-integrations.md §2): 가짜 구현과 벤더 어댑터가 같은 계약을 통과한다.
 * 벤더 어댑터(Anthropic)는 MockWebServer에 공개 문서의 Messages API 예시 응답을 넣어 요청·응답 변환을 본다. 실제 API는 부르지 않는다.
 */
class LlmProviderContractTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final LlmRequest REQUEST = new LlmRequest(1, 2L, LlmFeature.COMMENTARY, "[COMMENTARY] 해설을 쓴다",
            List.of(new DataSection("figures", "[{\"key\":\"anomalies\",\"label\":\"이상 건수\",\"value\":12}]"),
                    new DataSection("note", "연락처 kim@example.com")), 300);

    /** 모든 구현이 지켜야 하는 계약 */
    abstract static class Contract {
        abstract LlmInvoker invoker();

        abstract LlmProvider provider();

        @Test
        @DisplayName("[AIA-07.05] 계약: 사용 가능, 응답 글자·제공자·토큰 수(0보다 큼)·지연을 돌려준다")
        void completes() {
            LlmInvoker.Invocation inv = invoker().invoke(provider(), null, REQUEST);
            assertThat(invoker().available(provider())).isTrue();
            assertThat(inv.result().text()).isNotBlank();
            assertThat(inv.result().provider()).isEqualTo(provider());
            assertThat(inv.result().tokensIn()).isPositive();
            assertThat(inv.result().tokensOut()).isPositive();
            assertThat(inv.result().latencyMs()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("[AIA-07.03] 계약: 보내는 프롬프트는 가명 처리되고 정책 표지는 시스템 메시지에만")
        void masksBeforeSend() {
            LlmInvoker.Invocation inv = invoker().invoke(provider(), null, REQUEST);
            assertThat(inv.prompt().user()).doesNotContain("kim@example.com").contains("이메일#1");
            assertThat(inv.prompt().system()).contains(PromptAssembler.CANARY);
            assertThat(inv.prompt().user()).doesNotContain(PromptAssembler.CANARY);
        }
    }

    @Nested
    @DisplayName("FAKE(DemoChatModel)")
    class Fake extends Contract {
        final LlmInvoker invoker = new LlmInvoker(new ProviderRegistry(List.of(new FakeProvider(new DemoChatModel()))), Duration.ofSeconds(5),
                new MutableClock(Instant.parse("2026-10-05T00:00:00Z")));

        @Override
        LlmInvoker invoker() {
            return invoker;
        }

        @Override
        LlmProvider provider() {
            return LlmProvider.FAKE;
        }
    }

    @Nested
    @DisplayName("ANTHROPIC(MockWebServer, Messages API 예시 응답)")
    class Anthropic extends Contract {
        /** Messages API 공개 문서의 응답 모양(id·type·role·model·content[text]·stop_reason·usage) */
        static final String EXAMPLE = """
                {"id":"msg_01XFDUDYJgAACzvnptvVoYEL","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":"**요약** 최근 14일 중 이상 12건이 있었습니다."}],
                 "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":412,"output_tokens":38}}
                """;
        MockWebServer server;
        LlmInvoker invoker;
        AnthropicProvider provider;

        @BeforeEach
        void start() throws Exception {
            server = new MockWebServer();
            server.start();
            provider = new AnthropicProvider(new AiProperties.Anthropic("test-key-not-real", server.url("/").toString().replaceAll("/$", ""), null),
                    Duration.ofSeconds(10));
            invoker = new LlmInvoker(new ProviderRegistry(List.of(provider)), Duration.ofSeconds(20),
                    new MutableClock(Instant.parse("2026-10-05T00:00:00Z")));
            for (int i = 0; i < 2; i++) {
                server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(EXAMPLE));
            }
        }

        @AfterEach
        void stop() throws Exception {
            server.shutdown();
        }

        @Override
        LlmInvoker invoker() {
            return invoker;
        }

        @Override
        LlmProvider provider() {
            return LlmProvider.ANTHROPIC;
        }

        @Test
        @DisplayName("[AIA-07.05] 요청 모양: POST /v1/messages, x-api-key·anthropic-version, 모델 claude-opus-5-5, sampling 파라미터 없음, 응답 usage를 그대로 쓴다")
        void requestShape() throws Exception {
            LlmInvoker.Invocation inv = invoker.invoke(LlmProvider.ANTHROPIC, null, REQUEST);
            RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(req.getMethod()).isEqualTo("POST");
            assertThat(req.getPath()).isEqualTo("/v1/messages");
            assertThat(req.getHeader("x-api-key")).isEqualTo("test-key-not-real");
            assertThat(req.getHeader("anthropic-version")).isNotBlank();
            JsonNode body = JSON.readTree(req.getBody().readUtf8());
            assertThat(body.path("model").asString()).isEqualTo("claude-opus-5-5");
            assertThat(body.has("temperature")).isFalse();
            assertThat(body.has("top_p")).isFalse();
            assertThat(body.path("max_tokens").asInt()).isEqualTo(300);
            assertThat(body.toString()).contains(PromptAssembler.CANARY).doesNotContain("kim@example.com");
            assertThat(inv.result().text()).contains("이상 12건");
            assertThat(inv.result().tokensIn()).isEqualTo(412);
            assertThat(inv.result().tokensOut()).isEqualTo(38);
            assertThat(provider.note()).contains("사용 가능");
        }

        @Test
        @DisplayName("[AIA-07.05] 제공자 장애(529 overloaded, 재시도 뒤에도) → 503 AI_PROVIDER_UNAVAILABLE")
        void overloaded() throws Exception {
            server.shutdown();
            server = new MockWebServer();
            server.start();
            for (int i = 0; i < 4; i++) {
                server.enqueue(new MockResponse().setResponseCode(529).setHeader("Content-Type", "application/json")
                        .setBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"));
            }
            AnthropicProvider down = new AnthropicProvider(new AiProperties.Anthropic("k", server.url("/").toString().replaceAll("/$", ""), null),
                    Duration.ofSeconds(10));
            LlmInvoker failing = new LlmInvoker(new ProviderRegistry(List.of(down)), Duration.ofSeconds(30),
                    new MutableClock(Instant.parse("2026-10-05T00:00:00Z")));
            assertThatThrownBy(() -> failing.invoke(LlmProvider.ANTHROPIC, "claude-opus-5-5", REQUEST))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(AiErrorCode.AI_PROVIDER_UNAVAILABLE));
        }
    }

    @Test
    @DisplayName("[AIA-07.05] 키가 없으면 Anthropic은 '준비 중'이고 부르지 않는다, NONE·준비 중 제공자도 사용 불가")
    void unavailableProviders() {
        AnthropicProvider noKey = new AnthropicProvider(new AiProperties.Anthropic(null, null, null), Duration.ofSeconds(1));
        assertThat(noKey.available()).isFalse();
        assertThat(noKey.note()).contains("준비 중");
        assertThatThrownBy(() -> noKey.chatModel(null)).isInstanceOf(IllegalStateException.class);
        assertThat(new AiProperties.Anthropic("secret", null, null).toString()).doesNotContain("secret").contains("***");
        ProviderRegistry registry = new ProviderRegistry(List.of(noKey));
        LlmInvoker invoker = new LlmInvoker(registry, Duration.ofSeconds(1), new MutableClock(Instant.EPOCH));
        for (LlmProvider p : List.of(LlmProvider.NONE, LlmProvider.ANTHROPIC, LlmProvider.OPENAI, LlmProvider.GOOGLE, LlmProvider.OLLAMA)) {
            assertThat(invoker.available(p)).isFalse();
            assertThatThrownBy(() -> invoker.invoke(p, "m", REQUEST)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> registry.get(p).chatModel("m")).isInstanceOf(IllegalStateException.class);
            assertThat(registry.get(p).note()).isNotBlank();
        }
    }
}
