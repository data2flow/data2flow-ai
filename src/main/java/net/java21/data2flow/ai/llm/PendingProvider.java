package net.java21.data2flow.ai.llm;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 어댑터 자리만 있는 제공자(OPENAI·GOOGLE·OLLAMA). 등록 목록에는 보이지만 "준비 중"이다(external-integrations.md §1의 3단계).
 * 키가 생기면 Spring AI 해당 모듈로 {@link ChatModelProvider} 구현 하나를 더한다.
 */
public class PendingProvider implements ChatModelProvider {

    private final LlmProvider provider;

    public PendingProvider(LlmProvider provider) {
        this.provider = provider;
    }

    @Override
    public LlmProvider provider() {
        return provider;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public ChatModel chatModel(String model) {
        throw new IllegalStateException(provider + " 어댑터는 준비 중입니다");
    }

    @Override
    public String note() {
        return "준비 중(키 발급 후 어댑터 추가)";
    }
}
