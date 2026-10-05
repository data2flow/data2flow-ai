package net.java21.data2flow.ai.common;

import net.java21.data2flow.ai.help.HelpIndexer;
import net.java21.data2flow.ai.settings.AiSettings;
import net.java21.data2flow.ai.settings.AiSettingsRepository;
import net.java21.data2flow.ai.usage.UsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * 주기 작업. 여러 파드에서 함께 돌아도 결과가 같다(삭제·IF NOT EXISTS·내용이 같으면 쓰지 않는 색인).
 * <ul>
 *   <li>보관 기간 정리(BR-AIA-15): 조직 {@code log_retention_days}가 지난 프롬프트·응답 기록 삭제. 사용량(usage_logs)은 남긴다</li>
 *   <li>사용량 월 파티션: 이번 달·다음 달을 미리 만든다(pg_partman 없음, ADR-019)</li>
 *   <li>도움말 색인: 기동 때 배포본 문서로 갱신(AIA-09.01)</li>
 * </ul>
 */
public class AiJobs {

    private static final Logger log = LoggerFactory.getLogger(AiJobs.class);
    private final AiSettingsRepository settings;
    private final UsageRepository usage;
    private final HelpIndexer helpIndexer;
    private final Clock clock;

    public AiJobs(AiSettingsRepository settings, UsageRepository usage, HelpIndexer helpIndexer, Clock clock) {
        this.settings = settings;
        this.usage = usage;
        this.helpIndexer = helpIndexer;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            ensurePartitions();
            helpIndexer.index();
        } catch (RuntimeException e) {
            log.warn("기동 작업 실패(다음 주기에 다시)", e);
        }
    }

    @Scheduled(cron = "${data2flow.ai.retention.cron:0 17 3 * * *}", zone = "UTC")
    public void daily() {
        purge();
        ensurePartitions();
    }

    /** @return 지운 행 수 */
    public int purge() {
        Instant now = clock.instant();
        int deleted = 0;
        for (AiSettings s : settings.listAllForRetention()) {
            deleted += usage.deletePromptLogsByOrganizationIdBefore(s.organizationId(), now.minus(Duration.ofDays(s.logRetentionDays())));
        }
        deleted += usage.deletePromptLogsWithoutSettingsBefore(now.minus(Duration.ofDays(90)));
        if (deleted > 0) {
            log.info("AI 기록 {}건 정리(BR-AIA-15)", deleted);
        }
        return deleted;
    }

    public void ensurePartitions() {
        YearMonth month = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        for (YearMonth m : new YearMonth[]{month, month.plusMonths(1)}) {
            try {
                usage.ensureMonthPartition(m);
            } catch (RuntimeException e) {
                log.warn("usage_logs {} 파티션 준비 실패: {}", m, e.getMessage());
            }
        }
    }
}
