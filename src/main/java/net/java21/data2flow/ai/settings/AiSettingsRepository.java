package net.java21.data2flow.ai.settings;

import net.java21.data2flow.ai.llm.LlmProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** data2flow_ai.ai_settings(조직당 1행) */
@Repository
public class AiSettingsRepository {

    private static final RowMapper<AiSettings> ROW = (rs, i) -> new AiSettings(rs.getLong("organization_id"), rs.getBoolean("enabled"),
            LlmProvider.valueOf(rs.getString("provider")), rs.getString("model"), rs.getString("embedding_model"),
            rs.getInt("daily_request_limit"), rs.getLong("daily_token_limit"), rs.getInt("per_user_daily_limit"),
            rs.getInt("log_retention_days"), rs.getBoolean("auto_commentary"), rs.getBigDecimal("eval_threshold"),
            rs.getInt("suggestion_ttl_minutes"), rs.getInt("version"), rs.getTimestamp("updated_at").toInstant(), true);

    private final NamedParameterJdbcTemplate jdbc;

    public AiSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AiSettings> findByOrganizationId(long organizationId) {
        List<AiSettings> rows = jdbc.query("SELECT * FROM data2flow_ai.ai_settings WHERE organization_id = :org",
                new MapSqlParameterSource("org", organizationId), ROW);
        return rows.stream().findFirst();
    }

    /** 모든 조직의 보관 기간(정리 작업용) */
    @net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt("모든 조직을 도는 보관 기간 정리 배치(BR-AIA-15)")
    public List<AiSettings> listAllForRetention() {
        return jdbc.query("SELECT * FROM data2flow_ai.ai_settings", ROW);
    }

    /** 자동 해설이 켜진 조직인가 */
    public boolean existsAutoCommentaryByOrganizationId(long organizationId) {
        Boolean on = jdbc.query("SELECT enabled AND auto_commentary FROM data2flow_ai.ai_settings WHERE organization_id = :org",
                new MapSqlParameterSource("org", organizationId), rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE);
        return Boolean.TRUE.equals(on);
    }

    /** 처음 저장(version 1) */
    public void insert(AiSettings s, long userId, Instant now) {
        jdbc.update("""
                INSERT INTO data2flow_ai.ai_settings (organization_id, enabled, provider, model, embedding_model, daily_request_limit,
                    daily_token_limit, per_user_daily_limit, log_retention_days, auto_commentary, eval_threshold, suggestion_ttl_minutes,
                    version, created_by, updated_by, created_at, updated_at)
                VALUES (:org, :enabled, :provider, :model, :embedding, :dailyReq, :dailyTok, :perUser, :retention, :auto, :threshold, :ttl,
                    1, :user, :user, :now, :now)
                """, params(s, userId, now));
    }

    /** 낙관적 잠금 수정. 바뀐 행 수(0이면 버전 충돌) */
    public int updateByOrganizationId(AiSettings s, int baseVersion, long userId, Instant now) {
        return jdbc.update("""
                UPDATE data2flow_ai.ai_settings SET enabled = :enabled, provider = :provider, model = :model, embedding_model = :embedding,
                    daily_request_limit = :dailyReq, daily_token_limit = :dailyTok, per_user_daily_limit = :perUser,
                    log_retention_days = :retention, auto_commentary = :auto, eval_threshold = :threshold, suggestion_ttl_minutes = :ttl,
                    version = version + 1, updated_by = :user, updated_at = :now
                WHERE organization_id = :org AND version = :base
                """, params(s, userId, now).addValue("base", baseVersion));
    }

    private static MapSqlParameterSource params(AiSettings s, long userId, Instant now) {
        return new MapSqlParameterSource()
                .addValue("org", s.organizationId()).addValue("enabled", s.enabled()).addValue("provider", s.provider().name())
                .addValue("model", s.model()).addValue("embedding", s.embeddingModel()).addValue("dailyReq", s.dailyRequestLimit())
                .addValue("dailyTok", s.dailyTokenLimit()).addValue("perUser", s.perUserDailyLimit())
                .addValue("retention", s.logRetentionDays()).addValue("auto", s.autoCommentary()).addValue("threshold", s.evalThreshold())
                .addValue("ttl", s.suggestionTtlMinutes()).addValue("user", userId).addValue("now", Timestamp.from(now));
    }
}
