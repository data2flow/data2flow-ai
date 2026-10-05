package net.java21.data2flow.ai.llm;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 가짜 제공자(개발·시험·시연, ADR-040). 기본은 {@link DemoChatModel}(입력 수치만 인용하는 결정적 응답)이고,
 * 시험은 스크립트 재생·장애 흉내가 되는 ChatModel을 넣는다. 운영(prod)은 허용 목록에서 FAKE를 빼서 고를 수 없게 한다.
 */
public class FakeProvider implements ChatModelProvider {

    private final ChatModel chatModel;

    public FakeProvider(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.FAKE;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public ChatModel chatModel(String model) {
        return chatModel;
    }

    @Override
    public String defaultModel() {
        return DemoChatModel.MODEL;
    }

    @Override
    public String note() {
        return "가짜 구현(시연·시험용)";
    }
}
