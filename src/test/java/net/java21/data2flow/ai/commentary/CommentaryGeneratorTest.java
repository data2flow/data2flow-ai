package net.java21.data2flow.ai.commentary;

import net.java21.data2flow.ai.llm.LlmProvider;
import net.java21.data2flow.ai.llm.LlmRequest;
import net.java21.data2flow.ai.llm.LlmResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AIA-01.01·07.01 해설 생성 — TC-AIA-001·053, AIA-01.02 근거 링크 — TC-AIA-005 */
class CommentaryGeneratorTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final JsonNode RESULT = JSON.readTree(Fixtures.anomalyResult("실습실 CO2 센서"));

    @Test
    @DisplayName("[AIA-01.01][AT-AIA-01.1][TC-AIA-001] 요약·해석·권고 3구획, LLM에는 확정 수치와 메타정보만(원본 시계열 없음)")
    void threeParagraphsAndOnlyFigures() {
        List<LlmRequest> sent = new ArrayList<>();
        CommentaryGenerator.Outcome o = CommentaryGenerator.generate(1, 2L, RESULT, req -> {
            sent.add(req);
            return new LlmResult("**요약** 최근 14일 중 이상 12건, 최대 점수 5.2.\n\n**해석** 오후에 몰렸습니다.\n\n**권고** 환기를 늘리세요.",
                    LlmProvider.FAKE, "m", 10, 10, 1);
        });
        assertThat(o.verification().verified()).isTrue();
        assertThat(o.attempts()).isEqualTo(1);
        assertThat(o.text()).contains("**요약**").contains("**해석**").contains("**권고**");
        String data = sent.getFirst().data().toString();
        assertThat(data).contains("figures").contains("anomalies").contains("provenance").contains("periodDays");
        assertThat(data).doesNotContain("1240").doesNotContain("850").doesNotContain("\"series\"");
        assertThat(data).doesNotContain("김철수").contains("사용자#12");
    }

    @Test
    @DisplayName("[AIA-07.01][AT-AIA-01.2][TC-AIA-053] '15건'(입력 12) → 1회 재생성, 다시 15면 UNVERIFIED·불일치 [15]")
    void retryOnceThenUnverified() {
        List<LlmRequest> sent = new ArrayList<>();
        CommentaryGenerator.Outcome o = CommentaryGenerator.generate(1, 2L, RESULT, req -> {
            sent.add(req);
            return new LlmResult("이상 15건", LlmProvider.FAKE, "m", 5, 5, 1);
        });
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).task()).contains("mismatch 구획");
        assertThat(o.verification().verified()).isFalse();
        assertThat(o.verification().mismatches()).extracting(m -> m.value()).containsExactly("15");
        assertThat(o.tokensIn()).isEqualTo(10);
        assertThat(o.firstAttemptVerified()).isFalse();
    }

    @Test
    @DisplayName("[AIA-01.02][TC-AIA-005] '12건'은 이상 목록 표, '5.2'는 핵심 수치, 기준 3.0은 차트로 연결된다")
    void citations() {
        CitationLinker.Linked linked = CitationLinker.link("이상 12건, 최대 점수 5.2, 기준 3.0, 그리고 77", RESULT);
        assertThat(linked.contentMd()).contains("[12건](#result-table-anomalies)").contains("[5.2](#result-metric-maxScore)")
                .contains("(#result-") .endsWith("77");
        assertThat(linked.citations()).extracting(c -> c.target().type()).contains("TABLE", "METRIC");
        assertThat(CitationLinker.link("4.1점", RESULT).citations().getFirst().target()).isEqualTo(new CitationLinker.Target("TABLE", "anomalies"));
        assertThat(CitationLinker.link("숫자 없음", RESULT).citations()).isEmpty();
    }

    @Test
    @DisplayName("[AIA-01.01] summary.metrics가 객체 모양(analytics-service.md §4.3)이어도 읽는다, 기간을 못 읽으면 일수 없음")
    void objectMetrics() {
        JsonNode r = JSON.readTree("{\"summary\":{\"headline\":\"h\",\"metrics\":{\"anomalies\":3}},\"provenance\":{\"period\":{\"from\":\"x\"}}}");
        assertThat(CommentaryGenerator.figures(r.path("summary")).get(0).path("value").asInt()).isEqualTo(3);
        assertThat(CommentaryGenerator.periodDays(r.path("provenance").path("period"))).isNull();
        assertThat(CommentaryGenerator.sections(r)).extracting(s -> s.id()).contains("headline", "figures", "provenance");
    }
}
