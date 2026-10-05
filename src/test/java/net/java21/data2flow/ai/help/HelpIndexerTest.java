package net.java21.data2flow.ai.help;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** AIA-09.01 제품 문서 색인 자료 */
class HelpIndexerTest {

    @Test
    @DisplayName("[AIA-09.01] 사용자 가이드는 절 단위, 오류 코드는 코드마다 한 조각(출처 USER_GUIDE·TEMPLATE_GUIDE·ERROR_CODE와 링크)")
    void loads() {
        var chunks = HelpIndexer.load();
        assertThat(chunks).anyMatch(c -> c.docId().equals("guide:rules") && c.url().startsWith("/help/guide/rules#s"));
        assertThat(chunks).anyMatch(c -> c.source().equals("TEMPLATE_GUIDE"));
        assertThat(chunks).anyMatch(c -> c.docId().equals("error:AI_QUOTA_EXCEEDED") && c.url().equals("/help/errors#AI_QUOTA_EXCEEDED"));
        assertThat(chunks.stream().filter(c -> c.source().equals("ERROR_CODE")).count()).isGreaterThan(300);
        assertThat(HelpIndexer.guide("x", "## 제목\n본문")).singleElement().satisfies(c -> {
            assertThat(c.title()).isEqualTo("x — 제목");
            assertThat(c.source()).isEqualTo("USER_GUIDE");
        });
    }

    @Test
    @DisplayName("[AIA-09.01] 해시 임베딩은 1024차원 단위 벡터이고 같은 글은 같은 벡터")
    void embedding() {
        float[] a = HashingEmbedder.embed("규칙은 어떻게 만들어요?");
        float[] b = HashingEmbedder.embed("규칙은 어떻게 만들어요?");
        assertThat(a).hasSize(1024).containsExactly(b);
        double norm = 0;
        for (float x : a) {
            norm += x * x;
        }
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(HashingEmbedder.embed("")).containsOnly(0f);
        assertThat(HashingEmbedder.tokens("CO2 가 FLOW_NODE_TIMEOUT")).contains("co2", "가", "flow_node_timeout");
        assertThat(HashingEmbedder.literal(new float[]{1f, 0.5f})).isEqualTo("[1.0,0.5]");
    }
}
