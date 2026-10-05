package net.java21.data2flow.ai.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * 제공자 어댑터 SPI. 어댑터는 Spring AI {@link ChatModel} 하나만 만든다. 시간 제한·한도·기록은 게이트웨이 공통 계층이 맡는다
 * (external-integrations.md §2 "어댑터는 한 번 호출만 구현").
 */
public interface ChatModelProvider {

    LlmProvider provider();

    /** 키·설정이 있어 지금 쓸 수 있는가. false면 화면에 "준비 중" */
    boolean available();

    /** 이 모델로 부를 ChatModel. {@link #available()}이 false면 부르지 않는다 */
    ChatModel chatModel(String model);

    /**
     * 요청별 옵션. 제공자 전용 옵션 타입을 써야 하는 어댑터는 바꾼다(Spring AI 2.0의 AnthropicChatModel은 AnthropicChatOptions가 아니면
     * 요청 옵션을 버리고 기본값으로 보낸다).
     */
    default ChatOptions options(String model, int maxTokens) {
        ChatOptions.Builder<?> b = ChatOptions.builder().maxTokens(maxTokens);
        if (model != null) {
            b.model(model);
        }
        return b.build();
    }

    /** 모델 이름을 주지 않았을 때 쓰는 모델 */
    default String defaultModel() {
        return null;
    }

    /** 화면 표시용 설명(키를 넣는 법 등) */
    default String note() {
        return available() ? "사용 가능" : "준비 중";
    }
}
