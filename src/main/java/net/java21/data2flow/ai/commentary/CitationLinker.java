package net.java21.data2flow.ai.commentary;

import net.java21.data2flow.ai.safety.NumericGuard;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 해설 속 수치를 근거로 잇는다(AIA-01.02). 수치마다 결과의 핵심 수치(METRIC), 표(TABLE), 차트(CHART) 중 맞는 것을 찾아
 * 마크다운 링크 {@code [12건](#result-table-anomalies)}로 바꾸고 {@code citations[]}를 만든다. 화면은 앵커로 해당 표·차트로 스크롤해 강조한다.
 */
public final class CitationLinker {

    private CitationLinker() {
    }

    /** 근거 대상 */
    public record Target(String type, String id) {
        public String anchor() {
            return "#result-" + type.toLowerCase(java.util.Locale.ROOT) + "-" + id;
        }
    }

    /** 문장 속 수치와 근거 */
    public record Citation(String text, Target target) {
    }

    /** 링크를 넣은 본문과 근거 목록 */
    public record Linked(String contentMd, List<Citation> citations) {
    }

    public static Linked link(String text, JsonNode result) {
        List<Citation> citations = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (NumericGuard.Found f : NumericGuard.extract(text)) {
            Target target = target(f, result);
            if (target == null) {
                continue;
            }
            int at = text.indexOf(f.raw(), cursor);
            if (at < 0) {
                continue;
            }
            String shown = withCounter(text, at, f.raw());
            out.append(text, cursor, at).append('[').append(shown).append("](").append(target.anchor()).append(')');
            cursor = at + shown.length();
            citations.add(new Citation(shown, target));
        }
        out.append(text.substring(cursor));
        return new Linked(out.toString(), citations);
    }

    /** "12" 뒤의 단위·조사("건", "ppm", "℃", "%")까지 링크 글자에 넣는다 */
    private static String withCounter(String text, int at, String raw) {
        int end = at + raw.length();
        while (end < text.length() && end - at - raw.length() < 4) {
            char c = text.charAt(end);
            if (c == '건' || c == '개' || c == '회' || c == '%' || c == '℃' || c == 'p' || c == 'm') {
                end++;
            } else {
                break;
            }
        }
        return text.substring(at, end);
    }

    static Target target(NumericGuard.Found f, JsonNode result) {
        for (JsonNode fig : CommentaryGenerator.figures(result.path("summary"))) {
            if (fig.path("value").isNumber() && NumericGuard.matches(f, Set.of(fig.path("value").decimalValue()))) {
                String key = fig.path("key").asString();
                for (JsonNode table : result.path("tables")) {
                    if (key.equals(table.path("id").asString(null))) {
                        return new Target("TABLE", key);   // 이상 건수 → 이상 목록 표(AT-AIA-01.1)
                    }
                }
                return new Target("METRIC", key);
            }
        }
        for (JsonNode table : result.path("tables")) {
            String id = table.path("id").asString(table.path("title").asString("table"));
            if (NumericGuard.matches(f, Set.of(BigDecimal.valueOf(table.path("rows").size())))) {
                return new Target("TABLE", id);
            }
            for (JsonNode row : table.path("rows")) {
                if (NumericGuard.matches(f, NumericGuard.allowedFrom(row))) {
                    return new Target("TABLE", id);
                }
            }
        }
        for (JsonNode chart : result.path("charts")) {
            for (JsonNode t : chart.path("thresholds")) {
                if (t.path("value").isNumber() && NumericGuard.matches(f, Set.of(t.path("value").decimalValue()))) {
                    return new Target("CHART", chart.path("id").asString("chart"));
                }
            }
        }
        return null;
    }
}
