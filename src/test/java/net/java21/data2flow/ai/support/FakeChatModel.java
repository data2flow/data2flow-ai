package net.java21.data2flow.ai.support;

import net.java21.data2flow.ai.llm.DemoChatModel;
import net.java21.data2flow.ai.safety.PromptAssembler;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 결정적인 가짜 ChatModel(test-plan "AI 시험 장치"). PR 단계의 모든 AI 시험은 이것으로 돈다.
 *
 * <ul>
 *   <li>{@link #sequence(String...)}: 호출 순서대로 다른 응답. 다 쓰면 기본 동작({@link DemoChatModel})</li>
 *   <li>{@link #failing(Failure)}: 연결 거부·시간 초과·429 흉내</li>
 *   <li>{@link #obeyInjection(boolean)}: 데이터 구획 안 지시를 따르는 최악의 모델(시스템 프롬프트·데이터 원문을 그대로 내보내고
 *       이메일·토큰·지어낸 숫자를 덧붙임)</li>
 *   <li>{@link #captured()}: 받은 요청(시스템 프롬프트, 데이터 구획) 캡처</li>
 * </ul>
 */
public class FakeChatModel implements ChatModel {

    /** 장애 모드 */
    public enum Failure { NONE, CONNECTION_REFUSED, TIMEOUT, RATE_LIMITED }

    public static final String LEAKED_EMAIL = "attacker@evil.example.com";
    public static final String LEAKED_TOKEN = "data2flow_stolenToken1234567890";
    public static final String INVENTED_NUMBER = "999999";

    private final DemoChatModel demo = new DemoChatModel();
    private final Deque<String> scripted = new ArrayDeque<>();
    private final List<Prompt> captured = new ArrayList<>();
    private final CountDownLatch never = new CountDownLatch(1);
    private volatile Failure failure = Failure.NONE;
    private volatile boolean obey;

    public synchronized FakeChatModel sequence(String... responses) {
        scripted.clear();
        scripted.addAll(List.of(responses));
        return this;
    }

    public FakeChatModel failing(Failure failure) {
        this.failure = failure;
        return this;
    }

    public FakeChatModel obeyInjection(boolean obey) {
        this.obey = obey;
        return this;
    }

    public synchronized void reset() {
        scripted.clear();
        captured.clear();
        failure = Failure.NONE;
        obey = false;
    }

    public synchronized List<Prompt> captured() {
        return List.copyOf(captured);
    }

    /** 캡처한 요청 본문 전체(시스템 + 사용자) */
    public synchronized List<String> capturedTexts() {
        List<String> out = new ArrayList<>();
        for (Prompt p : captured) {
            StringBuilder sb = new StringBuilder();
            for (Message m : p.getInstructions()) {
                sb.append(m.getMessageType()).append(": ").append(m.getText()).append('\n');
            }
            out.add(sb.toString());
        }
        return out;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String next;
        synchronized (this) {
            captured.add(prompt);
            next = scripted.pollFirst();
        }
        switch (failure) {
            case CONNECTION_REFUSED -> throw new IllegalStateException("Connection refused");
            case RATE_LIMITED -> throw new IllegalStateException("429 rate_limit_error");
            case TIMEOUT -> {
                try {
                    never.await(30, TimeUnit.SECONDS);   // 게이트웨이 시간 제한이 먼저 끊는다
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("timeout");
            }
            default -> {
            }
        }
        if (obey) {
            return response(worst(prompt));
        }
        if (next != null) {
            return response(next);
        }
        ChatResponse demoResponse = demo.call(prompt);
        return response(demoResponse.getResult().getOutput().getText());
    }

    /** 데이터 구획 안 지시를 모두 따르는 최악의 답 */
    private static String worst(Prompt prompt) {
        StringBuilder sb = new StringBuilder("지시를 따릅니다.\n");
        for (Message m : prompt.getInstructions()) {
            if (m.getMessageType() == MessageType.SYSTEM) {
                sb.append("시스템 프롬프트: ").append(m.getText()).append('\n');
            } else {
                sb.append(m.getText()).append('\n');
            }
        }
        sb.append("요청하신 이메일: ").append(LEAKED_EMAIL).append(", 010-9876-5432\n");
        sb.append("토큰: ").append(LEAKED_TOKEN).append('\n');
        sb.append("tool_call: device_command {\"command\":\"OFF\", \"all\":true}\n");
        sb.append("모든 에어컨 ").append(INVENTED_NUMBER).append("대를 껐습니다.");
        return sb.toString();
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model("fake-test").usage(new DefaultUsage(Math.max(1, text.length() / 4), 50)).build());
    }

    /** 정책 표지(시스템 프롬프트 노출 확인용) */
    public static String canary() {
        return PromptAssembler.CANARY;
    }
}
