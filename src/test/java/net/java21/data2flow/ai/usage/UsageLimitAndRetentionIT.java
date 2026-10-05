package net.java21.data2flow.ai.usage;

import net.java21.data2flow.ai.common.AiErrorCode;
import net.java21.data2flow.ai.common.AiJobs;
import net.java21.data2flow.ai.settings.AiSettings;
import net.java21.data2flow.ai.support.AbstractIT;
import net.java21.data2flow.contracts.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** AIA-07.04 동시 한도(실제 Redis), AIA-07.06 보관 기간 정리(BR-AIA-15) */
class UsageLimitAndRetentionIT extends AbstractIT {

    @Autowired
    UsageLimiter limiter;
    @Autowired
    AiJobs jobs;

    @Test
    @DisplayName("[AIA-07.04][AT-AIA-07.1][TC-AIA-063] Redis 원자 카운터: 동시 요청 200건에서도 한도 100건만 허용")
    void concurrentQuota() throws Exception {
        long org = 1000 + (System.nanoTime() % 100_000);
        AiSettings s = new AiSettings(org, true, net.java21.data2flow.ai.llm.LlmProvider.FAKE, "m", "e", 100, 10_000_000L, 1000, 90, false,
                new java.math.BigDecimal("0.9"), 30, 1, null, true);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                long user = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        limiter.acquire(s, user);
                        allowed.incrementAndGet();
                    } catch (BusinessException e) {
                        assertThat(e.getErrorCode()).isEqualTo(AiErrorCode.AI_QUOTA_EXCEEDED);
                        limited.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(allowed.get()).isEqualTo(100);
        assertThat(limited.get()).isEqualTo(100);
        assertThat(limiter.usedRequestsToday(org)).isEqualTo(100);
    }

    @Test
    @DisplayName("[AIA-07.06][TC-AIA-070] 보관 기간 30일: 31일 지난 프롬프트·응답 기록은 지우고 사용량 집계는 남는다, 설정 없는 조직은 90일")
    void retention() {
        jdbc.update("INSERT INTO data2flow_ai.ai_settings (organization_id, enabled, provider, model, log_retention_days, version) VALUES (8, true, 'FAKE', 'm', 30, 1)");
        for (long org : new long[]{8, 9}) {
            jdbc.update("""
                    INSERT INTO data2flow_ai.prompt_logs (organization_id, time, feature, provider, model, prompt_masked, status)
                    VALUES (?, now() - interval '31 days', 'COMMENTARY', 'FAKE', 'm', 'old', 'OK'),
                           (?, now() - interval '91 days', 'COMMENTARY', 'FAKE', 'm', 'older', 'OK'),
                           (?, now() - interval '1 day', 'COMMENTARY', 'FAKE', 'm', 'new', 'OK')
                    """, org, org, org);
            jdbc.update("INSERT INTO data2flow_ai.usage_logs (organization_id, time, feature, provider, model, status) VALUES (?, now() - interval '31 days', 'COMMENTARY', 'FAKE', 'm', 'OK')",
                    org);
        }
        int deleted = jobs.purge();
        assertThat(deleted).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT prompt_masked FROM data2flow_ai.prompt_logs WHERE organization_id = 8", String.class)).containsExactly("new");
        assertThat(jdbc.queryForList("SELECT prompt_masked FROM data2flow_ai.prompt_logs WHERE organization_id = 9", String.class))
                .containsExactlyInAnyOrder("old", "new");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data2flow_ai.usage_logs WHERE organization_id IN (8, 9)", Integer.class)).isEqualTo(2);
        jobs.daily();
        jobs.ensurePartitions();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname = 'data2flow_ai' AND tablename LIKE 'usage_logs_y%'",
                Integer.class)).isGreaterThanOrEqualTo(2);
    }
}
