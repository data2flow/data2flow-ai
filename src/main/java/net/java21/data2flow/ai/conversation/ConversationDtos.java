package net.java21.data2flow.ai.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 대화 API 모양(API-AIA-02·10) */
public final class ConversationDtos {

    private ConversationDtos() {
    }

    /** 질문 {@code {content, mode, context}} */
    public record AskRequest(@NotBlank @Size(max = 2000) String content, @Pattern(regexp = "DATA|HELP") String mode,
                             Map<String, Object> context) {
    }

    /** 목록 항목 */
    public record Summary(String conversationId, String title, String mode, Instant createdAt, Instant updatedAt) {
    }

    /** 상세 */
    public record Detail(String conversationId, String title, String mode, Instant createdAt, Instant updatedAt, List<MessageView> messages) {
    }

    /** 메시지 */
    public record MessageView(String messageId, String role, String content, Object citations, Object verification, Instant createdAt) {
    }

    /** 스트림 이벤트 하나 */
    public record Event(String name, Object data) {
    }
}
