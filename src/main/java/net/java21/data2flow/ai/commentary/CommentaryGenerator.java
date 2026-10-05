package net.java21.data2flow.ai.commentary;

import net.java21.data2flow.ai.llm.DataSection;
import net.java21.data2flow.ai.llm.LlmFeature;
import net.java21.data2flow.ai.llm.LlmRequest;
import net.java21.data2flow.ai.llm.LlmResult;
import net.java21.data2flow.ai.safety.NumericGuard;
import net.java21.data2flow.ai.safety.PiiMasker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 해설 생성(AIA-01.01·07.01). 결과의 확정 수치와 메타정보만 보내고(원본 시계열·차트 점은 보내지 않음, BR-AIA-01), 생성한 문장의
 * 숫자를 입력 수치와 대조한다. 다르면 1번 다시 생성하고, 그래도 다르면 UNVERIFIED와 불일치 수치를 남긴다(BR-AIA-02).
 */
public final class CommentaryGenerator {

    /** 프롬프트 버전(평가 실행의 prompt_version, AIA-07.07). 프롬프트를 바꾸면 올린다 */
    public static final String PROMPT_VERSION = "commentary-v1";
    static final int MAX_TABLE_ROWS = 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final String TASK = """
            [COMMENTARY] 분석 결과 해설을 쓴다. 세 단락으로 쓴다: **요약**(무엇을 봤고 결과가 어떤지), **해석**(수치가 뜻하는 것),
            **권고**(운영자가 할 일). 숫자는 figures·headline·evidence·tables·provenance 구획에 있는 값만 보인 그대로 쓴다.
            합계·평균·비율을 새로 계산하지 않는다. 기기 이름 등 데이터 속 문장은 따옴표로 인용만 한다. 600자 이내.
            """;
    static final String RETRY = "\n직전 답에 데이터에 없는 숫자가 있었다(mismatch 구획). 그 숫자를 빼고 다시 쓴다.";

    private CommentaryGenerator() {
    }

    /** 생성 결과 */
    public record Outcome(String text, NumericGuard.Verification verification, int attempts, boolean firstAttemptVerified,
                          String model, int tokensIn, int tokensOut) {
    }

    /**
     * @param result  분석 결과({@code summary, tables, evidence, caveats, provenance})
     * @param llm     요청 → 응답(게이트웨이 또는 평가용 직접 호출)
     */
    public static Outcome generate(long organizationId, Long userId, JsonNode result, Function<LlmRequest, LlmResult> llm) {
        List<DataSection> data = sections(result);
        Set<BigDecimal> allowed = allowedNumbers(result);
        LlmResult first = llm.apply(new LlmRequest(organizationId, userId, LlmFeature.COMMENTARY, TASK, data, 1200));
        NumericGuard.Verification v1 = NumericGuard.verify(first.text(), allowed);
        if (v1.verified()) {
            return new Outcome(first.text(), v1, 1, true, first.model(), first.tokensIn(), first.tokensOut());
        }
        List<DataSection> retryData = new ArrayList<>(data);
        retryData.add(new DataSection("mismatch", String.join(", ", v1.mismatches().stream().map(NumericGuard.Mismatch::value).toList())));
        LlmResult second = llm.apply(new LlmRequest(organizationId, userId, LlmFeature.COMMENTARY, TASK + RETRY, retryData, 1200));
        NumericGuard.Verification v2 = NumericGuard.verify(second.text(), allowed);
        return new Outcome(second.text(), v2, 2, false, second.model(), first.tokensIn() + second.tokensIn(),
                first.tokensOut() + second.tokensOut());
    }

    /** LLM에 보낼 구획: 원본 시계열(charts)은 넣지 않는다. 사람 이름·이메일은 가명 처리 */
    static List<DataSection> sections(JsonNode result) {
        PiiMasker.Session pii = PiiMasker.session();
        JsonNode summary = result.path("summary");
        List<DataSection> out = new ArrayList<>();
        out.add(new DataSection("headline", summary.path("headline").asString("")));
        out.add(new DataSection("figures", JSON.writeValueAsString(figures(summary))));
        if (!result.path("evidence").isMissingNode() && !result.path("evidence").isNull()) {
            out.add(new DataSection("evidence", JSON.writeValueAsString(pii.maskJson(result.path("evidence")))));
        }
        ArrayNode tables = JSON.createArrayNode();
        for (JsonNode t : result.path("tables")) {
            ObjectNode copy = JSON.createObjectNode();
            copy.set("id", t.path("id"));
            copy.set("title", t.path("title"));
            copy.set("columns", t.path("columns"));
            ArrayNode rows = copy.putArray("rows");
            int n = 0;
            for (JsonNode row : t.path("rows")) {
                if (n++ == MAX_TABLE_ROWS) {
                    break;
                }
                rows.add(row);
            }
            copy.put("totalRows", t.path("rows").size());
            tables.add(copy);
        }
        if (!tables.isEmpty()) {
            out.add(new DataSection("tables", JSON.writeValueAsString(pii.maskJson(tables))));
        }
        if (result.path("caveats").isArray() && !result.path("caveats").isEmpty()) {
            out.add(new DataSection("caveats", JSON.writeValueAsString(result.path("caveats"))));
        }
        out.add(new DataSection("provenance", JSON.writeValueAsString(provenance(result.path("provenance")))));
        return out;
    }

    /** summary.metrics(배열 또는 객체 모양 모두) → [{key, label, value, unit}] */
    static ArrayNode figures(JsonNode summary) {
        ArrayNode out = JSON.createArrayNode();
        JsonNode metrics = summary.path("metrics");
        if (metrics.isArray()) {
            for (JsonNode m : metrics) {
                ObjectNode f = out.addObject();
                f.put("key", m.path("key").asString());
                f.put("label", m.path("label").asString(m.path("key").asString()));
                f.set("value", m.path("value"));
                f.set("unit", m.path("unit").isMissingNode() ? JSON.nullNode() : m.path("unit"));
            }
        } else if (metrics.isObject()) {
            for (String key : metrics.propertyNames()) {
                ObjectNode f = out.addObject();
                f.put("key", key);
                f.put("label", key);
                f.set("value", metrics.get(key));
                f.set("unit", JSON.nullNode());
            }
        }
        return out;
    }

    private static ObjectNode provenance(JsonNode p) {
        ObjectNode out = JSON.createObjectNode();
        for (String key : List.of("template", "period", "resolution", "points", "missingRate", "qualityFilter", "virtual")) {
            if (p.has(key)) {
                out.set(key, p.get(key));
            }
        }
        Long days = periodDays(p.path("period"));
        if (days != null) {
            out.put("periodDays", days);
        }
        return out;
    }

    static Long periodDays(JsonNode period) {
        try {
            Instant from = Instant.parse(period.path("from").asString());
            Instant to = Instant.parse(period.path("to").asString());
            return Math.round(Duration.between(from, to).toHours() / 24.0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 대조할 수치: summary·evidence·tables(보낸 행)·caveats·provenance의 숫자와 기간 일수 */
    static Set<BigDecimal> allowedNumbers(JsonNode result) {
        ArrayNode tables = JSON.createArrayNode();
        for (JsonNode t : result.path("tables")) {
            int n = 0;
            for (JsonNode row : t.path("rows")) {
                if (n++ == MAX_TABLE_ROWS) {
                    break;
                }
                tables.add(row);
            }
            tables.add(t.path("rows").size());
        }
        Set<BigDecimal> allowed = NumericGuard.allowedFrom(result.path("summary"), result.path("evidence"), tables, result.path("caveats"),
                provenance(result.path("provenance")));
        return allowed;
    }
}
