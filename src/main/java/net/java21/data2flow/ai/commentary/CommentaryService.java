package net.java21.data2flow.ai.commentary;

import net.java21.data2flow.ai.common.AiErrorCode;
import net.java21.data2flow.ai.llm.LlmGateway;
import net.java21.data2flow.ai.safety.NumericGuard;
import net.java21.data2flow.ai.settings.AiSettings;
import net.java21.data2flow.ai.settings.AiSettingsService;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 분석 결과 해설(AIA-01.01~03, AIA-07.01). 권한: 생성 ANALYST 이상(ANALYTICS_RUN), 조회 ANALYTICS_READ. 둘 다 AI_USE.
 * M6는 {@code ANALYSIS_RUN}만 받는다(REPORT는 정기 리포트 AIA-02와 함께 M7).
 */
public class CommentaryService {

    public static final String SUBJECT_ANALYSIS_RUN = "ANALYSIS_RUN";

    private final CommentaryRepository repository;
    private final SubjectLoader loader;
    private final LlmGateway gateway;
    private final AiSettingsService settings;
    private final RoleChecker roleChecker;
    private final Clock clock;

    public CommentaryService(CommentaryRepository repository, SubjectLoader loader, LlmGateway gateway, AiSettingsService settings,
                             RoleChecker roleChecker, Clock clock) {
        this.repository = repository;
        this.loader = loader;
        this.gateway = gateway;
        this.settings = settings;
        this.roleChecker = roleChecker;
        this.clock = clock;
    }

    /** 생성 결과(스트림으로 내보낼 내용) */
    public record Generated(Commentary commentary, List<CitationLinker.Citation> citations, NumericGuard.Verification verification,
                            int tokensIn, int tokensOut) {
    }

    public Generated create(CommentaryRequest req) {
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        settings.requireEnabled(org);
        roleChecker.require(Permission.ANALYTICS_RUN);
        roleChecker.require(Permission.AI_USE);
        if (!SUBJECT_ANALYSIS_RUN.equals(req.subjectType())) {
            throw new BusinessException(AiErrorCode.AI_COMMENTARY_SUBJECT_INVALID);
        }
        long runId = Long.parseLong(req.subjectId());
        JsonNode subject = loader.loadRun(user, runId, req.analysisId() == null ? null : Long.parseLong(req.analysisId()));
        if (!req.regenerate()) {
            Optional<Commentary> existing = repository.findLatestDoneByOrganizationIdAndSubject(org, SUBJECT_ANALYSIS_RUN, runId);
            if (existing.isPresent()) {
                Commentary c = existing.get();
                return new Generated(c, List.of(), new NumericGuard.Verification("VERIFIED".equals(c.status()), c.mismatches(), 0), 0, 0);
            }
        }
        if (!gateway.available(org)) {
            throw new BusinessException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        return generateAndStore(org, user.userId(), runId, subject.path("result"));
    }

    /** 생성·검증·저장. 자동 해설(EVT-ANA-01)도 여기로 온다(userId null) */
    public Generated generateAndStore(long org, Long userId, long runId, JsonNode result) {
        AiSettings s = settings.effective(org);
        long id = repository.insertGenerating(org, SUBJECT_ANALYSIS_RUN, runId, s.model(), clock.instant());
        CommentaryGenerator.Outcome outcome;
        try {
            outcome = CommentaryGenerator.generate(org, userId, result, gateway::complete);
        } catch (BusinessException e) {
            repository.updateResultByOrganizationId(org, id, "FAILED", "", s.model(), List.of(), clock.instant());
            throw e;
        }
        CitationLinker.Linked linked = CitationLinker.link(outcome.text(), result);
        NumericGuard.Verification v = outcome.verification();
        repository.updateResultByOrganizationId(org, id, v.status(), linked.contentMd(), outcome.model(), v.mismatches(), clock.instant());
        repository.updateSupersededByOrganizationId(org, SUBJECT_ANALYSIS_RUN, runId, id);
        Commentary saved = repository.listByOrganizationIdAndSubject(org, SUBJECT_ANALYSIS_RUN, runId).getFirst();
        return new Generated(saved, linked.citations(), v, outcome.tokensIn(), outcome.tokensOut());
    }

    public List<Commentary> list(String subjectType, long subjectId) {
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        settings.requireEnabled(org);
        roleChecker.require(Permission.ANALYTICS_READ);
        roleChecker.require(Permission.AI_USE);
        if (!SUBJECT_ANALYSIS_RUN.equals(subjectType)) {
            throw new BusinessException(AiErrorCode.AI_COMMENTARY_SUBJECT_INVALID);
        }
        try {
            loader.loadRun(user, subjectId, null);   // 볼 수 있는 결과인가(권한 밖이면 404)
        } catch (BusinessException e) {
            if (e.getErrorCode() != AiErrorCode.AI_COMMENTARY_SUBJECT_INVALID) {
                throw e;
            }
        }
        return repository.listByOrganizationIdAndSubject(org, subjectType, subjectId);
    }
}
