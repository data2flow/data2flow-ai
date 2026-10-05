package net.java21.data2flow.ai.llm;

import org.springframework.ai.chat.model.ChatModel;

/** 키가 없을 때의 기본값(AIA-07.05). 쓰는 쪽은 "AI 사용 불가" 동작(템플릿 대체·숨김)을 가진다 */
public class NoneProvider implements ChatModelProvider {

    @Override
    public LlmProvider provider() {
        return LlmProvider.NONE;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public ChatModel chatModel(String model) {
        throw new IllegalStateException("LLM 제공자가 설정되지 않았습니다(NONE)");
    }

    @Override
    public String note() {
        return "LLM 없음(수치 요약 템플릿으로 대체)";
    }
}
