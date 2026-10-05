package net.java21.data2flow.ai.common;

import net.java21.data2flow.contracts.error.ErrorCode;

/** AIA 도메인 오류 코드(spec/detail/AIA/domain-model.md "오류 코드"). 문구는 messages*.properties의 {@code error.<코드>} */
public enum AiErrorCode implements ErrorCode {
    AI_DISABLED(409),
    AI_QUOTA_EXCEEDED(429),
    AI_PROVIDER_UNAVAILABLE(503),
    AI_CONVERSATION_NOT_FOUND(404),
    AI_COMMENTARY_SUBJECT_INVALID(400),
    AI_EVAL_BELOW_THRESHOLD(409),
    MCP_RATE_LIMITED(429);

    private final int httpStatus;

    AiErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
