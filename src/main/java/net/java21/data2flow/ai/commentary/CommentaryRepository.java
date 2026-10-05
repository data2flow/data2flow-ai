package net.java21.data2flow.ai.commentary;

import net.java21.data2flow.ai.safety.NumericGuard;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** data2flow_ai.commentaries */
@Repository
public class CommentaryRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<NumericGuard.Mismatch>> MISMATCHES = new TypeReference<>() {
    };
    private static final RowMapper<Commentary> ROW = (rs, i) -> new Commentary(Long.toString(rs.getLong("id")), rs.getString("subject_type"),
            Long.toString(rs.getLong("subject_id")), rs.getString("status"), rs.getString("content_md"), rs.getString("model"),
            rs.getString("mismatches") == null ? List.of() : JSON.readValue(rs.getString("mismatches"), MISMATCHES),
            rs.getObject("superseded_by") == null ? null : Long.toString(rs.getLong("superseded_by")),
            rs.getTimestamp("created_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public CommentaryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** GENERATING 행을 만든다 */
    public long insertGenerating(long organizationId, String subjectType, long subjectId, String model, Instant now) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO data2flow_ai.commentaries (organization_id, subject_type, subject_id, status, model, created_at, updated_at)
                VALUES (:org, :type, :sid, 'GENERATING', :model, :now, :now)
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("type", subjectType).addValue("sid", subjectId)
                .addValue("model", model).addValue("now", Timestamp.from(now)), key, new String[]{"id"});
        return key.getKey().longValue();
    }

    public void updateResultByOrganizationId(long organizationId, long id, String status, String contentMd, String model,
                                             List<NumericGuard.Mismatch> mismatches, Instant now) {
        jdbc.update("""
                UPDATE data2flow_ai.commentaries SET status = :status, content_md = :content, model = :model,
                    mismatches = CAST(:mismatches AS jsonb), updated_at = :now
                WHERE id = :id AND organization_id = :org
                """, new MapSqlParameterSource().addValue("status", status).addValue("content", contentMd == null ? "" : contentMd)
                .addValue("model", model).addValue("mismatches", mismatches == null || mismatches.isEmpty() ? null : JSON.writeValueAsString(mismatches))
                .addValue("now", Timestamp.from(now)).addValue("id", id).addValue("org", organizationId));
    }

    /** 다시 생성: 이전 해설들에 새 id를 연결(이전 버전은 남는다, AIA-01.03) */
    public void updateSupersededByOrganizationId(long organizationId, String subjectType, long subjectId, long newId) {
        jdbc.update("""
                UPDATE data2flow_ai.commentaries SET superseded_by = :new
                WHERE organization_id = :org AND subject_type = :type AND subject_id = :sid AND id <> :new AND superseded_by IS NULL
                """, new MapSqlParameterSource().addValue("new", newId).addValue("org", organizationId).addValue("type", subjectType)
                .addValue("sid", subjectId));
    }

    /** 이력(최신순, 페이징 없음) */
    public List<Commentary> listByOrganizationIdAndSubject(long organizationId, String subjectType, long subjectId) {
        return jdbc.query("""
                SELECT * FROM data2flow_ai.commentaries WHERE organization_id = :org AND subject_type = :type AND subject_id = :sid
                ORDER BY created_at DESC, id DESC
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("type", subjectType).addValue("sid", subjectId), ROW);
    }

    /** 최신 완료 해설(VERIFIED·UNVERIFIED) */
    public Optional<Commentary> findLatestDoneByOrganizationIdAndSubject(long organizationId, String subjectType, long subjectId) {
        return jdbc.query("""
                SELECT * FROM data2flow_ai.commentaries WHERE organization_id = :org AND subject_type = :type AND subject_id = :sid
                  AND status IN ('VERIFIED','UNVERIFIED') ORDER BY created_at DESC, id DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("type", subjectType).addValue("sid", subjectId), ROW)
                .stream().findFirst();
    }
}
