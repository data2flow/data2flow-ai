package net.java21.data2flow.ai.script;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** data2flow_ai.script_assists(시도는 attempts jsonb 배열, 최대 5) */
@Repository
public class ScriptAssistRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ScriptAssistRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 저장된 도움 한 건 */
    public record Row(long id, long userId, Long scriptId, String stage, String requirement, String attemptsJson) {
    }

    public long insert(long organizationId, long userId, Long scriptId, String stage, String requirement, String attemptsJson, Instant now) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO data2flow_ai.script_assists (organization_id, user_id, script_id, stage, requirement, attempts, created_at, updated_at)
                VALUES (:org, :user, :script, :stage, :req, CAST(:attempts AS jsonb), :now, :now)
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("user", userId).addValue("script", scriptId)
                .addValue("stage", stage).addValue("req", requirement).addValue("attempts", attemptsJson)
                .addValue("now", Timestamp.from(now)), key, new String[]{"id"});
        return key.getKey().longValue();
    }

    /** 본인 것만(남의 것은 없는 것과 같다) */
    public Optional<Row> findByIdAndOrganizationIdAndUserId(long id, long organizationId, long userId) {
        List<Row> rows = jdbc.query("""
                SELECT id, user_id, script_id, stage, requirement, attempts::text AS attempts FROM data2flow_ai.script_assists
                WHERE id = :id AND organization_id = :org AND user_id = :user
                """, new MapSqlParameterSource().addValue("id", id).addValue("org", organizationId).addValue("user", userId),
                (rs, i) -> new Row(rs.getLong("id"), rs.getLong("user_id"), (Long) rs.getObject("script_id"), rs.getString("stage"),
                        rs.getString("requirement"), rs.getString("attempts")));
        return rows.stream().findFirst();
    }

    public void updateAttemptsByOrganizationId(long organizationId, long id, String attemptsJson, Instant now) {
        jdbc.update("UPDATE data2flow_ai.script_assists SET attempts = CAST(:attempts AS jsonb), updated_at = :now WHERE id = :id AND organization_id = :org",
                new MapSqlParameterSource().addValue("attempts", attemptsJson).addValue("now", Timestamp.from(now)).addValue("id", id)
                        .addValue("org", organizationId));
    }
}
