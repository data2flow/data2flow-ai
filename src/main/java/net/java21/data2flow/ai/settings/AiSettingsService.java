package net.java21.data2flow.ai.settings;

import net.java21.data2flow.ai.common.AiErrorCode;
import net.java21.data2flow.ai.common.AiProperties;
import net.java21.data2flow.ai.eval.EvalRun;
import net.java21.data2flow.ai.eval.EvalRunRepository;
import net.java21.data2flow.ai.llm.LlmProvider;
import net.java21.data2flow.ai.llm.ProviderRegistry;
import net.java21.data2flow.contracts.audit.AuditEvent;
import net.java21.data2flow.contracts.audit.AuditRecorder;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI 설정(AIA-07.05, API-AIA-07)과 AI 꺼짐 판정(AI_DISABLED), 모델 변경 평가 기준(AIA-07.07, BR-AIA-10).
 */
public class AiSettingsService {

    private final AiSettingsRepository repository;
    private final EvalRunRepository evalRuns;
    private final ProviderRegistry providers;
    private final RoleChecker roleChecker;
    private final AuditRecorder audit;
    private final AiProperties properties;
    private final Clock clock;

    public AiSettingsService(AiSettingsRepository repository, EvalRunRepository evalRuns, ProviderRegistry providers, RoleChecker roleChecker,
                             AuditRecorder audit, AiProperties properties, Clock clock) {
        this.repository = repository;
        this.evalRuns = evalRuns;
        this.providers = providers;
        this.roleChecker = roleChecker;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** 조직의 현재 설정(행이 없으면 배포 기본값) */
    public AiSettings effective(long organizationId) {
        return repository.findByOrganizationId(organizationId)
                .orElseGet(() -> AiSettings.defaults(organizationId, properties.llm().defaultProvider(), properties.llm().defaultModel()));
    }

    /** AI가 꺼진 조직이면 409 AI_DISABLED(API-AIA-07 제외 모든 AI API) */
    public AiSettings requireEnabled(long organizationId) {
        AiSettings s = effective(organizationId);
        if (!s.enabled()) {
            throw new BusinessException(AiErrorCode.AI_DISABLED);
        }
        return s;
    }

    public AiSettingsResponse get() {
        roleChecker.requireAdmin();
        return AiSettingsResponse.of(effective(roleChecker.currentUser().organizationId()), providerStatuses());
    }

    @Transactional
    public AiSettingsResponse update(AiSettingsRequest req) {
        roleChecker.requireAdmin();
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        AiSettings current = effective(org);
        VersionCheck.require(req.baseVersion(), current.version());
        if (!properties.llm().allowedProviders().contains(req.provider())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail("provider", "NOT_ALLOWED", "이 배포에서 고를 수 없는 제공자입니다")));
        }
        boolean modelChanged = current.provider() != req.provider() || !Objects.equals(current.model(), req.model());
        if (modelChanged && req.provider() != LlmProvider.NONE) {
            requireEvalPassed(org, req.model(), req.evalThreshold());
        }
        AiSettings next = new AiSettings(org, req.enabled(), req.provider(), req.model(),
                req.embeddingModel() == null || req.embeddingModel().isBlank() ? current.embeddingModel() : req.embeddingModel(),
                req.dailyRequestLimit(), req.dailyTokenLimit(), req.perUserDailyLimit(), req.logRetentionDays(), req.autoCommentary(),
                req.evalThreshold(), req.suggestionTtlMinutes(), current.version() + 1, null, true);
        Instant now = clock.instant();
        if (current.persisted()) {
            VersionCheck.requireUpdated(repository.updateByOrganizationId(next, req.baseVersion(), user.userId(), now));
        } else {
            repository.insert(next, user.userId(), now);
        }
        audit.record(AuditEvent.builder(org, "AI_SETTINGS_CHANGED").actor(user).target("AI_SETTINGS", Long.toString(org))
                .detail("before", summary(current)).detail("after", summary(next)).build());
        return AiSettingsResponse.of(effective(org), providerStatuses());
    }

    /** BR-AIA-10: 이 모델의 최근 평가 정확도가 기준 이상이어야 한다. 평가 이력이 없으면 적용할 수 없다 */
    void requireEvalPassed(long org, String model, java.math.BigDecimal threshold) {
        EvalRun last = evalRuns.findLatestFinishedByOrganizationIdAndModel(org, model).orElse(null);
        if (last == null || last.accuracy() == null || last.accuracy().compareTo(threshold) < 0) {
            String pct = threshold.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
            throw new BusinessException(AiErrorCode.AI_EVAL_BELOW_THRESHOLD, pct);
        }
    }

    List<AiSettingsResponse.ProviderStatus> providerStatuses() {
        return Arrays.stream(LlmProvider.values())
                .map(p -> new AiSettingsResponse.ProviderStatus(p.name(), providers.get(p).available(),
                        properties.llm().allowedProviders().contains(p), providers.get(p).note()))
                .toList();
    }

    private static Map<String, Object> summary(AiSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled());
        m.put("provider", s.provider().name());
        m.put("model", s.model());
        m.put("dailyRequestLimit", s.dailyRequestLimit());
        m.put("dailyTokenLimit", s.dailyTokenLimit());
        m.put("perUserDailyLimit", s.perUserDailyLimit());
        m.put("logRetentionDays", s.logRetentionDays());
        m.put("autoCommentary", s.autoCommentary());
        return m;
    }
}
