package net.java21.data2flow.ai.usage;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** data2flow_ai.usage_logs(사용량, 월 파티션)와 prompt_logs(마스킹한 프롬프트·응답, 보관 기간 정리) */
@Repository
public class UsageRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UsageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertUsage(UsageRecord r) {
        jdbc.update("""
                INSERT INTO data2flow_ai.usage_logs (organization_id, time, user_id, feature, provider, model, tokens_in, tokens_out,
                    cost_estimate, status)
                VALUES (:org, :time, :user, :feature, :provider, :model, :tin, :tout, :cost, :status)
                """, new MapSqlParameterSource().addValue("org", r.organizationId()).addValue("time", Timestamp.from(r.time()))
                .addValue("user", r.userId()).addValue("feature", r.feature()).addValue("provider", r.provider())
                .addValue("model", r.model()).addValue("tin", r.tokensIn()).addValue("tout", r.tokensOut())
                .addValue("cost", r.costEstimate()).addValue("status", r.status()));
    }

    public void insertPromptLog(UsageRecord r, String promptMasked, String response, String toolCallsJson, long latencyMs) {
        jdbc.update("""
                INSERT INTO data2flow_ai.prompt_logs (organization_id, time, user_id, feature, provider, model, prompt_masked, response,
                    tool_calls, tokens_in, tokens_out, latency_ms, status)
                VALUES (:org, :time, :user, :feature, :provider, :model, :prompt, :response, CAST(:tools AS jsonb), :tin, :tout, :latency,
                    :status)
                """, new MapSqlParameterSource().addValue("org", r.organizationId()).addValue("time", Timestamp.from(r.time()))
                .addValue("user", r.userId()).addValue("feature", r.feature()).addValue("provider", r.provider())
                .addValue("model", r.model()).addValue("prompt", promptMasked).addValue("response", response)
                .addValue("tools", toolCallsJson).addValue("tin", r.tokensIn()).addValue("tout", r.tokensOut())
                .addValue("latency", (int) Math.min(Integer.MAX_VALUE, latencyMs)).addValue("status", r.status()));
    }

    /**
     * 기간 사용량 묶음. groupBy: day(조직 시간대 날짜), feature, user.
     *
     * @param userId 본인 사용량(API-AIA-08 /usage/me)이면 사용자, 전체면 null
     */
    public List<Map<String, Object>> aggregateByOrganizationId(long organizationId, Instant from, Instant to, String groupBy, String zone,
                                                               Long userId) {
        String key = switch (groupBy) {
            case "feature" -> "feature";
            case "user" -> "COALESCE(CAST(user_id AS varchar), 'SYSTEM')";
            default -> "to_char(time AT TIME ZONE :zone, 'YYYY-MM-DD')";
        };
        String sql = "SELECT " + key + " AS k, count(*) FILTER (WHERE status <> 'LIMITED') AS requests, sum(tokens_in) AS tokens_in,"
                + " sum(tokens_out) AS tokens_out, COALESCE(sum(cost_estimate), 0) AS cost, count(*) FILTER (WHERE status = 'LIMITED') AS limited"
                + " FROM data2flow_ai.usage_logs WHERE organization_id = :org AND time >= :from AND time < :to"
                + (userId == null ? "" : " AND user_id = :user")
                + " GROUP BY 1 ORDER BY 1";
        return jdbc.queryForList(sql, new MapSqlParameterSource().addValue("org", organizationId).addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to)).addValue("zone", zone).addValue("user", userId));
    }

    /** 보관 기간이 지난 프롬프트·응답 삭제. 사용량(usage_logs)은 남긴다(BR-AIA-15) */
    public int deletePromptLogsByOrganizationIdBefore(long organizationId, Instant cutoff) {
        return jdbc.update("DELETE FROM data2flow_ai.prompt_logs WHERE organization_id = :org AND time < :cutoff",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("cutoff", Timestamp.from(cutoff)));
    }

    /** 설정 행이 없는 조직(기본 90일)의 기록 정리 */
    @net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt("설정 행이 없는 모든 조직을 기본 보관 기간으로 정리하는 배치")
    public int deletePromptLogsWithoutSettingsBefore(Instant cutoff) {
        return jdbc.update("""
                DELETE FROM data2flow_ai.prompt_logs p WHERE p.time < :cutoff
                  AND NOT EXISTS (SELECT 1 FROM data2flow_ai.ai_settings s WHERE s.organization_id = p.organization_id)
                """, new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)));
    }

    /** 월 파티션 준비(pg_partman 없음, ADR-019). 이미 있으면 그대로 */
    public void ensureMonthPartition(YearMonth month) {
        String name = "usage_logs_y%dm%02d".formatted(month.getYear(), month.getMonthValue());
        Instant from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        jdbc.getJdbcTemplate().execute("CREATE TABLE IF NOT EXISTS data2flow_ai." + name + " PARTITION OF data2flow_ai.usage_logs"
                + " FOR VALUES FROM ('" + from + "') TO ('" + to + "')");
    }

    /** 한 건 */
    public record UsageRecord(long organizationId, Instant time, Long userId, String feature, String provider, String model,
                              int tokensIn, int tokensOut, BigDecimal costEstimate, String status) {
    }
}
