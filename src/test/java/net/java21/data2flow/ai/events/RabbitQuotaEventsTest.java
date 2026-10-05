package net.java21.data2flow.ai.events;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.MessageSchemas;
import net.java21.data2flow.contracts.message.event.AiQuotaExceeded;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.contracts.test.message.MessageFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** AIA-07.04 EVT-AIA-03: ai.quota.exceeded를 계약 봉투(domain-event.v1)·공통 헤더로 data2flow.events에 내고, confirm nack이면 되돌린다 */
class RabbitQuotaEventsTest {

    private static final Instant T = Instant.parse("2026-10-05T05:12:00Z");
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final MessageCodec codec = MessageCodec.create();
    private final RabbitQuotaEvents events = new RabbitQuotaEvents(rabbit, codec, Runnable::run, Clock.fixed(T, ZoneOffset.UTC));

    private void confirm(boolean ack) {
        doAnswer(inv -> {
            inv.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nack"));
            return null;
        }).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }

    @Test
    @DisplayName("[AIA-07.04][EVT-AIA-03] 라우팅 키 ai.quota.exceeded, 본문은 스키마 통과·공유 픽스처와 같은 페이로드, 헤더 messageId·schema·organizationId")
    void publishesContractEnvelope() {
        // given
        confirm(true);
        AtomicInteger failures = new AtomicInteger();
        AiQuotaExceeded payload = AiQuotaExceeded.user(7, AiQuotaExceeded.LimitType.REQUESTS, Instant.parse("2026-10-05T15:00:00Z"));
        // when
        events.exceeded(1, payload, failures::incrementAndGet);
        // then
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq(MessagingNames.EXCHANGE_EVENTS), eq("ai.quota.exceeded"), sent.capture(), any(CorrelationData.class));
        byte[] body = sent.getValue().getBody();
        MessageSchemas.assertValid(MessageSchemas.DOMAIN_EVENT, codec.mapper().readTree(body));
        DomainEvent<?> read = codec.readEvent(body);
        assertThat(read.eventType()).isEqualTo(EventType.AI_QUOTA_EXCEEDED);
        assertThat(read.payload()).isEqualTo(MessageFixtures.domainEvent("ai-quota-exceeded-user").payload());
        assertThat(sent.getValue().getMessageProperties().getHeaders())
                .containsEntry("schema", "ai.quota.exceeded").containsEntry("organizationId", "1")
                .containsEntry("messageId", read.messageId().toString());
        assertThat(failures).hasValue(0);
    }

    @Test
    @DisplayName("[AIA-07.04][EVT-AIA-03] 브로커가 nack하면 onFailure(하루 한 번 표시 되돌림)")
    void nackCallsOnFailure() {
        confirm(false);
        AtomicInteger failures = new AtomicInteger();
        events.exceeded(1, AiQuotaExceeded.organization(AiQuotaExceeded.LimitType.TOKENS, T), failures::incrementAndGet);
        assertThat(failures).hasValue(1);
    }
}
