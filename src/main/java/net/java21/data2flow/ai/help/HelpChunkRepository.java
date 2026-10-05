package net.java21.data2flow.ai.help;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** data2flow_ai.help_chunks(전역 제품 문서 색인, 조직 없음) */
@Repository
@OrganizationScopeExempt("제품 문서 색인은 모든 조직이 함께 쓰는 전역 자원(ERD help_chunks에 organization_id 없음)")
public class HelpChunkRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public HelpChunkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 검색 결과 */
    public record Hit(String source, String docId, int chunkNo, String chunk, String url, double score) {
    }

    public void upsert(String source, String docId, int chunkNo, String chunk, float[] embedding, String url) {
        jdbc.update("""
                INSERT INTO data2flow_ai.help_chunks (source, doc_id, chunk_no, chunk, embedding, url)
                VALUES (:source, :doc, :no, :chunk, CAST(:emb AS public.vector), :url)
                ON CONFLICT (doc_id, chunk_no) DO UPDATE SET source = EXCLUDED.source, chunk = EXCLUDED.chunk,
                    embedding = EXCLUDED.embedding, url = EXCLUDED.url, updated_at = now()
                WHERE help_chunks.chunk IS DISTINCT FROM EXCLUDED.chunk OR help_chunks.url IS DISTINCT FROM EXCLUDED.url
                """, new MapSqlParameterSource().addValue("source", source).addValue("doc", docId).addValue("no", chunkNo)
                .addValue("chunk", chunk).addValue("emb", HashingEmbedder.literal(embedding)).addValue("url", url));
    }

    /** 코사인 거리 오름차순 상위 n(점수 = 1 - 거리) */
    public List<Hit> search(float[] query, int limit) {
        return jdbc.query("""
                SELECT source, doc_id, chunk_no, chunk, url,
                       1 - (embedding OPERATOR(public.<=>) CAST(:q AS public.vector)) AS score
                FROM data2flow_ai.help_chunks
                ORDER BY embedding OPERATOR(public.<=>) CAST(:q AS public.vector) LIMIT :limit
                """, new MapSqlParameterSource().addValue("q", HashingEmbedder.literal(query)).addValue("limit", limit),
                (rs, i) -> new Hit(rs.getString("source"), rs.getString("doc_id"), rs.getInt("chunk_no"), rs.getString("chunk"),
                        rs.getString("url"), rs.getDouble("score")));
    }

    /** 오류 코드 정확 일치 */
    public List<Hit> findByDocId(String docId) {
        return jdbc.query("SELECT source, doc_id, chunk_no, chunk, url FROM data2flow_ai.help_chunks WHERE doc_id = :doc ORDER BY chunk_no",
                new MapSqlParameterSource("doc", docId),
                (rs, i) -> new Hit(rs.getString("source"), rs.getString("doc_id"), rs.getInt("chunk_no"), rs.getString("chunk"),
                        rs.getString("url"), 1.0));
    }
}
