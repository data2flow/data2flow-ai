package net.java21.data2flow.ai.usage;

import net.java21.data2flow.contracts.message.event.AiQuotaExceeded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EVT-AIA-03 {@code ai.quota.exceeded} 발행 창구(AIA-07.04). {@link UsageLimiter}가 같은 한도마다 하루 한 번만 부른다.
 * 발행은 요청 처리(429 응답)를 막지 않아야 하므로 비동기로 하고, 실패하면 {@code onFailure}를 불러 다음 초과 때 다시 내게 한다.
 */
@FunctionalInterface
public interface QuotaEvents {

    /** 이벤트 소비·발행이 꺼진 배포(로컬 등): 로그만 남긴다 */
    QuotaEvents LOG_ONLY = new QuotaEvents() {
        private final Logger log = LoggerFactory.getLogger(QuotaEvents.class);

        @Override
        public void exceeded(long organizationId, AiQuotaExceeded event, Runnable onFailure) {
            log.info("AI 사용량 한도 도달(이벤트 발행 꺼짐) org={} scope={} limitType={}", organizationId, event.scope(), event.limitType());
        }
    };

    void exceeded(long organizationId, AiQuotaExceeded event, Runnable onFailure);
}
