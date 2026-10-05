package net.java21.data2flow.ai.eval;

import net.java21.data2flow.ai.commentary.CommentaryGenerator;
import net.java21.data2flow.ai.common.AiErrorCode;
import net.java21.data2flow.ai.llm.LlmInvoker;
import net.java21.data2flow.ai.llm.LlmProvider;
import net.java21.data2flow.ai.settings.AiSettings;
import net.java21.data2flow.ai.settings.AiSettingsService;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * API-AIA-16 평가(AD). 사례 목록, 평가 실행(202, 백그라운드), 실행 목록. 실행 결과는 모델 적용 판단(BR-AIA-10)에 쓰인다.
 * 제공자는 요청의 {@code provider}(추가 필드) 또는 조직 설정의 제공자다. 평가 호출은 조직 한도를 쓰지 않는다.
 */
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final EvalRunRepository runs;
    private final LlmInvoker invoker;
    private final AiSettingsService settings;
    private final RoleChecker roleChecker;
    private final ExecutorService executor;
    private final Clock clock;

    public EvalService(EvalRunRepository runs, LlmInvoker invoker, AiSettingsService settings, RoleChecker roleChecker, ExecutorService executor,
                       Clock clock) {
        this.runs = runs;
        this.invoker = invoker;
        this.settings = settings;
        this.roleChecker = roleChecker;
        this.executor = executor;
        this.clock = clock;
    }

    /** 사례 응답 항목 */
    public record CaseView(String evalSetId, String caseId, String kind, String question, List<java.math.BigDecimal> expectedNumbers,
                           boolean expectedRefusal, boolean injection, String category) {
    }

    /** 실행 요청 {@code {model, promptVersion, provider?}} */
    public record RunRequest(String model, String promptVersion, LlmProvider provider) {
    }

    /** 실행 응답 항목 */
    public record RunView(String runId, String evalSetId, String model, String promptVersion, java.math.BigDecimal accuracy,
                          java.math.BigDecimal numberMatchRate, java.math.BigDecimal injectionBlockRate, boolean passed, java.time.Instant createdAt) {
        static RunView of(EvalRun r) {
            return new RunView(Long.toString(r.id()), Long.toString(r.evalSetId()), r.model(), r.promptVersion(), r.accuracy(), r.numberMatchRate(),
                    r.injectionBlockRate(), r.passed(), r.createdAt());
        }
    }

    public List<CaseView> cases() {
        begin();
        long setId = setId();
        return EvalSuite.load().stream().map(c -> new CaseView(Long.toString(setId), c.caseId(), c.kind(), c.question(), c.expectedNumbers(),
                c.expectedRefusal(), c.injection(), c.category())).toList();
    }

    public RunView start(RunRequest req) {
        CurrentUser user = begin();
        AiSettings s = settings.effective(user.organizationId());
        LlmProvider provider = req.provider() == null ? s.provider() : req.provider();
        String model = req.model() == null || req.model().isBlank() ? s.model() : req.model();
        String promptVersion = req.promptVersion() == null || req.promptVersion().isBlank() ? CommentaryGenerator.PROMPT_VERSION : req.promptVersion();
        if (!invoker.available(provider)) {
            throw new BusinessException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        long setId = setId();
        long runId = runs.insertRun(user.organizationId(), setId, model, promptVersion, clock.instant());
        long org = user.organizationId();
        java.math.BigDecimal threshold = s.evalThreshold();
        executor.execute(() -> {
            try {
                EvalHarness.Report r = EvalHarness.run(EvalSuite.load(), req2 -> invoker.invoke(provider, model, req2).result(), threshold);
                runs.updateRunResultByOrganizationId(org, runId, r.accuracy(), r.numberMatchRate(), r.injectionBlockRate(), r.passed());
                log.info("평가 실행 {} 끝: 정확도 {}, 불일치율 {}, 인젝션 성공 {}건", runId, r.accuracy(), r.mismatchRate(), r.injectionSucceeded());
            } catch (RuntimeException e) {
                log.warn("평가 실행 {} 실패", runId, e);
                runs.updateRunResultByOrganizationId(org, runId, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        false);
            }
        });
        return runs.findRunByOrganizationId(org, runId).map(RunView::of).orElseThrow();
    }

    public ListApiResponse<RunView> list(Integer page, Integer size) {
        CurrentUser user = begin();
        PageParams p = PageParams.of(page, size);
        return ListApiResponse.of(p, runs.pageByOrganizationId(user.organizationId(), p.offset(), p.size()).stream().map(RunView::of).toList(),
                runs.countByOrganizationId(user.organizationId()));
    }

    private CurrentUser begin() {
        roleChecker.requireAdmin();
        CurrentUser user = roleChecker.currentUser();
        settings.requireEnabled(user.organizationId());
        return user;
    }

    private long setId() {
        return runs.upsertSetByOrganizationId(EvalRunRepository.GLOBAL_ORG, EvalSuite.NAME, JSON.writeValueAsString(EvalSuite.load()));
    }
}
