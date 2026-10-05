package net.java21.data2flow.ai.conversation;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** data2flow_ai.conversations·messages. 본인 것만 읽고 지운다(BR-AIA-14). 삭제는 물리 삭제(메시지 CASCADE) */
@Repository
public class ConversationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ConversationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertConversation(long organizationId, long userId, String title, String mode, Instant now) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO data2flow_ai.conversations (organization_id, user_id, title, mode, created_at, updated_at)
                VALUES (:org, :user, :title, :mode, :now, :now)
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("user", userId).addValue("title", title)
                .addValue("mode", mode).addValue("now", Timestamp.from(now)), key, new String[]{"id"});
        return key.getKey().longValue();
    }

    public void insertMessage(long organizationId, long conversationId, String role, String content, String citationsJson, Integer tokensIn,
                              Integer tokensOut, Instant now) {
        jdbc.update("""
                INSERT INTO data2flow_ai.messages (organization_id, conversation_id, role, content, citations, tokens_in, tokens_out, created_at)
                VALUES (:org, :conv, :role, :content, CAST(:citations AS jsonb), :tin, :tout, :now)
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("conv", conversationId).addValue("role", role)
                .addValue("content", content).addValue("citations", citationsJson).addValue("tin", tokensIn).addValue("tout", tokensOut)
                .addValue("now", Timestamp.from(now)));
        jdbc.update("UPDATE data2flow_ai.conversations SET updated_at = :now WHERE id = :id AND organization_id = :org",
                new MapSqlParameterSource().addValue("now", Timestamp.from(now)).addValue("id", conversationId).addValue("org", organizationId));
    }

    public Optional<Map<String, Object>> findByIdAndOrganizationIdAndUserId(long id, long organizationId, long userId) {
        return jdbc.queryForList("""
                SELECT id, title, mode, created_at, updated_at FROM data2flow_ai.conversations
                WHERE id = :id AND organization_id = :org AND user_id = :user
                """, new MapSqlParameterSource().addValue("id", id).addValue("org", organizationId).addValue("user", userId))
                .stream().findFirst();
    }

    public List<Map<String, Object>> pageByOrganizationIdAndUserId(long organizationId, long userId, long offset, int size) {
        return jdbc.queryForList("""
                SELECT id, title, mode, created_at, updated_at FROM data2flow_ai.conversations
                WHERE organization_id = :org AND user_id = :user ORDER BY updated_at DESC, id DESC LIMIT :size OFFSET :offset
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("user", userId).addValue("size", size)
                .addValue("offset", offset));
    }

    public long countByOrganizationIdAndUserId(long organizationId, long userId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM data2flow_ai.conversations WHERE organization_id = :org AND user_id = :user",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("user", userId), Long.class);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> listMessagesByOrganizationId(long organizationId, long conversationId) {
        return jdbc.queryForList("""
                SELECT id, role, content, citations::text AS citations, verification::text AS verification, created_at FROM data2flow_ai.messages
                WHERE organization_id = :org AND conversation_id = :conv ORDER BY id
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("conv", conversationId));
    }

    public int deleteByIdAndOrganizationIdAndUserId(long id, long organizationId, long userId) {
        return jdbc.update("DELETE FROM data2flow_ai.conversations WHERE id = :id AND organization_id = :org AND user_id = :user",
                new MapSqlParameterSource().addValue("id", id).addValue("org", organizationId).addValue("user", userId));
    }

    public int deleteAllByOrganizationIdAndUserId(long organizationId, long userId) {
        return jdbc.update("DELETE FROM data2flow_ai.conversations WHERE organization_id = :org AND user_id = :user",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("user", userId));
    }
}
