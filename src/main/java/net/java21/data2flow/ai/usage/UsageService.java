package net.java21.data2flow.ai.usage;

import net.java21.data2flow.ai.settings.AiSettings;
import net.java21.data2flow.ai.settings.AiSettingsService;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** API-AIA-08 사용량(AIA-07.04·06). 합계는 usage_logs에서 내므로 프롬프트 기록을 지워도 남는다(BR-AIA-15) */
public class UsageService {

    private static final Set<String> GROUPS = Set.of("day", "feature", "user");
    private final UsageRepository repository;
    private final UsageLimiter limiter;
    private final AiSettingsService settings;
    private final RoleChecker roleChecker;
    private final ZoneId zone;
    private final Clock clock;

    public UsageService(UsageRepository repository, UsageLimiter limiter, AiSettingsService settings, RoleChecker roleChecker, ZoneId zone,
                        Clock clock) {
        this.repository = repository;
        this.limiter = limiter;
        this.settings = settings;
        this.roleChecker = roleChecker;
        this.zone = zone;
        this.clock = clock;
    }

    /** 응답 {@code {series[], totals, limits}} */
    public record Usage(List<Row> series, Totals totals, Limits limits) {
    }

    public record Row(String key, long requests, long tokensIn, long tokensOut, BigDecimal costEstimate, boolean limited) {
    }

    public record Totals(long requests, long tokensIn, long tokensOut, BigDecimal costEstimate) {
    }

    public record Limits(int dailyRequestLimit, long dailyTokenLimit, int perUserDailyLimit, long usedRequestsToday, long usedTokensToday,
                         Instant resetAt) {
    }

    /** 조직 전체(AD) */
    public Usage organization(Instant from, Instant to, String groupBy) {
        roleChecker.requireAdmin();
        return usage(roleChecker.currentUser(), from, to, groupBy, null);
    }

    /** 본인(V 이상) */
    public Usage mine(Instant from, Instant to, String groupBy) {
        roleChecker.require(Permission.AI_USE);
        CurrentUser user = roleChecker.currentUser();
        return usage(user, from, to, groupBy, user.userId());
    }

    private Usage usage(CurrentUser user, Instant from, Instant to, String groupBy, Long userId) {
        long org = user.organizationId();
        AiSettings s = settings.requireEnabled(org);
        String group = groupBy == null ? "day" : groupBy;
        if (!GROUPS.contains(group)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("groupBy", "INVALID", "day|feature|user")));
        }
        Instant t = to == null ? clock.instant() : to;
        Instant f = from == null ? t.minus(30, ChronoUnit.DAYS) : from;
        if (!f.isBefore(t)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("from", "INVALID", "from < to")));
        }
        List<Row> rows = new ArrayList<>();
        long req = 0;
        long tin = 0;
        long tout = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (Map<String, Object> r : repository.aggregateByOrganizationId(org, f, t, group, zone.getId(), userId)) {
            Row row = new Row(String.valueOf(r.get("k")), num(r.get("requests")), num(r.get("tokens_in")), num(r.get("tokens_out")),
                    (BigDecimal) r.get("cost"), num(r.get("limited")) > 0);
            rows.add(row);
            req += row.requests();
            tin += row.tokensIn();
            tout += row.tokensOut();
            cost = cost.add(row.costEstimate() == null ? BigDecimal.ZERO : row.costEstimate());
        }
        return new Usage(rows, new Totals(req, tin, tout, cost), new Limits(s.dailyRequestLimit(), s.dailyTokenLimit(), s.perUserDailyLimit(),
                limiter.usedRequestsToday(org), limiter.usedTokensToday(org), limiter.resetAt()));
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
