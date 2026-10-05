package net.java21.data2flow.ai.events;

import net.java21.data2flow.ai.usage.QuotaEvents;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.contracts.messaging.QuorumQueueSpec;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ExecutorService;

/**
 * 이벤트 소비(큐 {@code ai.events}, Quorum + DLX, ADR-020). {@code data2flow.ai.events.enabled=true}일 때만(기본 꺼짐).
 * 지금 받는 이벤트: EVT-ANA-01 {@code analytics.run.succeeded}(자동 해설). 내는 이벤트: EVT-AIA-03 {@code ai.quota.exceeded}
 * ({@link RabbitQuotaEvents}, 꺼져 있으면 로그만).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "data2flow.ai.events", name = "enabled", havingValue = "true")
public class EventsConfig {

    static final QuorumQueueSpec EVENTS = QuorumQueueSpec.events("ai");

    @Bean
    Declarables aiDeclarables() {
        TopicExchange events = new TopicExchange(MessagingNames.EXCHANGE_EVENTS, true, false);
        DirectExchange dlx = new DirectExchange(MessagingNames.EXCHANGE_DLX, true, false);
        Queue queue = new Queue(EVENTS.name(), true, false, false, EVENTS.arguments());
        Queue dlq = new Queue(EVENTS.deadLetterQueue(), true, false, false, QuorumQueueSpec.deadLetterArguments());
        return new Declarables(events, dlx, queue, dlq,
                BindingBuilder.bind(queue).to(events).with(AutoCommentaryHandler.ROUTING_KEY),
                BindingBuilder.bind(dlq).to(dlx).with(EVENTS.name()));
    }

    @Bean
    QuotaEvents rabbitQuotaEvents(RabbitTemplate rabbitTemplate, ExecutorService aiBackgroundExecutor, Clock clock) {
        return new RabbitQuotaEvents(rabbitTemplate, MessageCodec.create(), aiBackgroundExecutor, clock);
    }

    @Bean
    SimpleMessageListenerContainer aiEventContainer(ConnectionFactory connectionFactory, AutoCommentaryHandler handler) {
        SimpleMessageListenerContainer c = new SimpleMessageListenerContainer(connectionFactory);
        c.setQueueNames(EVENTS.name());
        c.setAcknowledgeMode(AcknowledgeMode.AUTO);   // 처리(DB 저장) 뒤 ACK
        c.setPrefetchCount(EVENTS.prefetch());
        c.setConcurrentConsumers(1);
        c.setDefaultRequeueRejected(true);
        c.setMissingQueuesFatal(false);
        c.setShutdownTimeout(20_000);
        c.setMessageListener(message -> handler.handle(message.getBody()));
        return c;
    }
}
