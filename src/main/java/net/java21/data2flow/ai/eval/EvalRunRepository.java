package net.java21.data2flow.ai.eval;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * data2flow_ai.eval_sets·eval_runs. 평가 셋은 코드와 함께 배포되는 전역 자원이라 조직 0으로 둔다(ERD 기본값 0).
 */
@Repository
public class EvalRunRepository {

    public static final long GLOBAL_ORG = 0L;
    private static final RowMapper<EvalRun> ROW = (rs, i) -> new EvalRun(rs.getLong("id"), rs.getLong("eval_set_id"), rs.getString("model"),
            rs.getString("prompt_version"), rs.getBigDecimal("accuracy"), rs.getBigDecimal("number_match_rate"),
            rs.getBigDecimal("injection_block_rate"), rs.getBoolean("passed"), rs.getTimestamp("created_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public EvalRunRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 평가 셋 행(이름별 1행, 사례 JSON은 배포본으로 갱신) */
    public long upsertSetByOrganizationId(long organizationId, String name, String casesJson) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM data2flow_ai.eval_sets WHERE organization_id = :org AND name = :name",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("name", name), Long.class);
        if (!ids.isEmpty()) {
            jdbc.update("UPDATE data2flow_ai.eval_sets SET cases = CAST(:cases AS jsonb), updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource().addValue("cases", casesJson).addValue("id", ids.getFirst()));
            return ids.getFirst();
        }
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO data2flow_ai.eval_sets (organization_id, name, cases) VALUES (:org, :name, CAST(:cases AS jsonb))",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("name", name).addValue("cases", casesJson), key,
                new String[]{"id"});
        return key.getKey().longValue();
    }

    public long insertRun(long organizationId, long evalSetId, String model, String promptVersion, Instant now) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO data2flow_ai.eval_runs (organization_id, eval_set_id, model, prompt_version, passed, created_at)
                VALUES (:org, :set, :model, :pv, false, :now)
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("set", evalSetId).addValue("model", model)
                .addValue("pv", promptVersion).addValue("now", Timestamp.from(now)), key, new String[]{"id"});
        return key.getKey().longValue();
    }

    public void updateRunResultByOrganizationId(long organizationId, long runId, BigDecimal accuracy, BigDecimal numberMatchRate,
                                                BigDecimal injectionBlockRate, boolean passed) {
        jdbc.update("""
                UPDATE data2flow_ai.eval_runs SET accuracy = :acc, number_match_rate = :num, injection_block_rate = :inj, passed = :passed
                WHERE id = :id AND organization_id = :org
                """, new MapSqlParameterSource().addValue("acc", accuracy).addValue("num", numberMatchRate).addValue("inj", injectionBlockRate)
                .addValue("passed", passed).addValue("id", runId).addValue("org", organizationId));
    }

    /** 조직 실행과 전역(배포 전 평가) 실행을 함께, 최신순 */
    public List<EvalRun> pageByOrganizationId(long organizationId, long offset, int size) {
        return jdbc.query("""
                SELECT * FROM data2flow_ai.eval_runs WHERE organization_id IN (:org, 0) ORDER BY created_at DESC, id DESC
                LIMIT :size OFFSET :offset
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("size", size).addValue("offset", offset), ROW);
    }

    public long countByOrganizationId(long organizationId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM data2flow_ai.eval_runs WHERE organization_id IN (:org, 0)",
                new MapSqlParameterSource("org", organizationId), Long.class);
        return n == null ? 0 : n;
    }

    /** 이 모델의 가장 최근 끝난 평가(BR-AIA-10 적용 판단) */
    public Optional<EvalRun> findLatestFinishedByOrganizationIdAndModel(long organizationId, String model) {
        return jdbc.query("""
                SELECT * FROM data2flow_ai.eval_runs WHERE organization_id IN (:org, 0) AND model = :model AND accuracy IS NOT NULL
                ORDER BY created_at DESC, id DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("model", model), ROW).stream().findFirst();
    }

    public Optional<EvalRun> findRunByOrganizationId(long organizationId, long runId) {
        return jdbc.query("SELECT * FROM data2flow_ai.eval_runs WHERE id = :id AND organization_id IN (:org, 0)",
                new MapSqlParameterSource().addValue("id", runId).addValue("org", organizationId), ROW).stream().findFirst();
    }
}
