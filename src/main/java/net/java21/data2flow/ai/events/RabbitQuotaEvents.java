package net.java21.data2flow.ai.events;

import net.java21.data2flow.ai.usage.QuotaEvents;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.event.AiQuotaExceeded;
import net.java21.data2flow.contracts.messaging.MessageHeaders;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * EVT-AIA-03 {@code ai.quota.exceeded} 발행({@code data2flow.events}, 라우팅 키 = 종류). 봉투는 contracts {@link DomainEvent},
 * 헤더는 {@link MessageHeaders#of}(messageId·v·schema·organizationId·occurredAt·X-REQUEST-ID). 영속 메시지이고 publisher confirm(ack)을
 * 받아야 성공이다. 요청(429 응답)을 막지 않도록 백그라운드에서 보내고, 실패하면 {@code onFailure}로 하루 한 번 표시를 되돌린다.
 */
public class RabbitQuotaEvents implements QuotaEvents {

    static final long CONFIRM_TIMEOUT_SECONDS = 5;
    private static final Logger log = LoggerFactory.getLogger(RabbitQuotaEvents.class);

    private final RabbitTemplate rabbit;
    private final MessageCodec codec;
    private final Executor executor;
    private final Clock clock;

    public RabbitQuotaEvents(RabbitTemplate rabbit, MessageCodec codec, Executor executor, Clock clock) {
        this.rabbit = rabbit;
        this.codec = codec;
        this.executor = executor;
        this.clock = clock;
    }

    @Override
    public void exceeded(long organizationId, AiQuotaExceeded payload, Runnable onFailure) {
        DomainEvent<AiQuotaExceeded> event = DomainEvent.of(EventType.AI_QUOTA_EXCEEDED, organizationId, payload, MDC.get("requestId"), clock);
        executor.execute(() -> {
            try {
                send(event);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("ai.quota.exceeded 발행 실패 org={} (다음 초과 때 다시 보냄)", organizationId, e);
                onFailure.run();
            }
        });
    }

    void send(DomainEvent<AiQuotaExceeded> event) throws Exception {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(event.messageId().toString());
        MessageHeaders.of(event).forEach(props::setHeader);
        CorrelationData correlation = new CorrelationData(event.messageId().toString());
        rabbit.send(MessagingNames.EXCHANGE_EVENTS, event.type(), new Message(codec.write(event), props), correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!confirm.ack()) {
            throw new IllegalStateException("RabbitMQ nack: " + confirm.reason());
        }
    }
}
