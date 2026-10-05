package net.java21.data2flow.ai.llm;

import net.java21.data2flow.ai.common.AiErrorCode;
import net.java21.data2flow.ai.safety.OutputFilter;
import net.java21.data2flow.ai.safety.PiiMasker;
import net.java21.data2flow.ai.safety.PromptAssembler;
import net.java21.data2flow.contracts.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 제공자 한 번 호출(설정·한도와 무관한 공통 계층). 프롬프트 조립·가명 처리 → ChatModel 호출(시간 제한) → 출력 필터.
 * {@link DefaultLlmGateway}와 평가 하네스({@code EvalHarness})가 함께 쓴다.
 */
public class LlmInvoker {

    private static final Logger log = LoggerFactory.getLogger(LlmInvoker.class);
    private final ProviderRegistry providers;
    private final Duration timeout;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LlmInvoker(ProviderRegistry providers, Duration timeout, Clock clock) {
        this.providers = providers;
        this.timeout = timeout;
        this.clock = clock;
    }

    /** 조립된 프롬프트(가명 처리 후). 기록·요청 캡처 검증에 쓴다 */
    public record Assembled(String system, String user, PiiMasker.Session pii) {
        public String combined() {
            return "[system]\n" + system + "\n[user]\n" + user;
        }
    }

    public static Assembled assemble(LlmRequest request) {
        PiiMasker.Session pii = PiiMasker.session();
        return new Assembled(PromptAssembler.system(request), PromptAssembler.user(request, pii), pii);
    }

    /** 결과와 조립된 프롬프트 */
    public record Invocation(LlmResult result, Assembled prompt) {
    }

    public boolean available(LlmProvider provider) {
        return providers.get(provider).available();
    }

    /** 제공자 오류·시간 초과 → 503 AI_PROVIDER_UNAVAILABLE */
    public Invocation invoke(LlmProvider provider, String model, LlmRequest request) {
        ChatModelProvider p = providers.get(provider);
        if (!p.available()) {
            throw new BusinessException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        Assembled assembled = assemble(request);
        String effectiveModel = model == null || model.isBlank() ? p.defaultModel() : model;
        ChatModel chat = p.chatModel(effectiveModel);
        Prompt prompt = new Prompt(List.of(new SystemMessage(assembled.system()), new UserMessage(assembled.user())),
                p.options(effectiveModel, request.maxTokens()));
        long started = clock.millis();
        ChatResponse response;
        Future<ChatResponse> future = executor.submit(() -> chat.call(prompt));
        try {
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("LLM 시간 초과 provider={} model={}", provider, model);
            throw new BusinessException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
        } catch (ExecutionException e) {
            log.warn("LLM 호출 실패 provider={} model={}: {}", provider, model, e.getCause() == null ? e : e.getCause().toString());
            throw new BusinessException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        long latency = clock.millis() - started;
        String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? "" : response.getResult().getOutput().getText();
        String filtered = OutputFilter.filter(text == null ? "" : text);
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        int in = usage == null || usage.getPromptTokens() == null || usage.getPromptTokens() == 0
                ? estimate(assembled.combined()) : usage.getPromptTokens();
        int out = usage == null || usage.getCompletionTokens() == null || usage.getCompletionTokens() == 0
                ? estimate(filtered) : usage.getCompletionTokens();
        String usedModel = response != null && response.getMetadata() != null && response.getMetadata().getModel() != null
                && !response.getMetadata().getModel().isBlank() ? response.getMetadata().getModel() : effectiveModel;
        return new Invocation(new LlmResult(filtered, provider, usedModel, in, out, latency), assembled);
    }

    /** 제공자가 사용량을 주지 않을 때의 추정(문자 4개 ≈ 토큰 1개) */
    static int estimate(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}
