package net.java21.data2flow.ai.llm;

import net.java21.data2flow.ai.common.AiProperties;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anthropic 어댑터(Spring AI {@link AnthropicChatModel}, 안쪽은 공식 anthropic-java SDK). 키가 없으면 "준비 중"이고 호출하지 않는다.
 *
 * <p>키를 넣는 법: k8s Secret {@code data2flow-ai-llm}의 {@code anthropic-api-key} → 환경변수
 * {@code DATA2FLOW_AI_ANTHROPIC_API_KEY}. 그다음 관리자가 AI 설정(API-AIA-07)에서 제공자를 ANTHROPIC으로 바꾼다
 * (모델을 바꾸면 평가 기준을 넘은 실행이 있어야 한다, BR-AIA-10).
 *
 * <p>기본 모델은 {@code claude-opus-5-5}. 이 모델은 sampling 파라미터(temperature 등)를 받지 않으므로 넣지 않는다.
 * 재시도는 SDK 기본(429·5xx·연결 오류 2회)에 맡기고, 시간 제한은 게이트웨이 설정을 따른다.
 */
public class AnthropicProvider implements ChatModelProvider {

    private final AiProperties.Anthropic settings;
    private final Duration timeout;
    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();

    public AnthropicProvider(AiProperties.Anthropic settings, Duration timeout) {
        this.settings = settings;
        this.timeout = timeout;
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.ANTHROPIC;
    }

    @Override
    public boolean available() {
        return settings.configured();
    }

    @Override
    public ChatModel chatModel(String model) {
        if (!available()) {
            throw new IllegalStateException("Anthropic API 키가 없습니다");
        }
        String name = model == null || model.isBlank() ? settings.model() : model;
        return models.computeIfAbsent(name, this::build);
    }

    private ChatModel build(String model) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .apiKey(settings.apiKey())
                .baseUrl(settings.baseUrl())
                .model(model)
                .maxTokens(4096)
                .maxRetries(2)
                .timeout(timeout)
                .build();
        return AnthropicChatModel.builder().options(options).build();
    }

    /** 요청마다 AnthropicChatOptions로(모델·최대 토큰·시간 제한). temperature 등 sampling 값은 넣지 않는다 */
    @Override
    public org.springframework.ai.chat.prompt.ChatOptions options(String model, int maxTokens) {
        return AnthropicChatOptions.builder().model(model == null ? settings.model() : model).maxTokens(maxTokens).timeout(timeout).build();
    }

    @Override
    public String defaultModel() {
        return settings.model();
    }

    @Override
    public String note() {
        return available() ? "사용 가능(" + settings.model() + ")" : "준비 중(API 키 Secret 없음)";
    }
}
