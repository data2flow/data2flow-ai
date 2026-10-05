package net.java21.data2flow.ai.mcp;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import net.java21.data2flow.ai.common.CoreClient;
import net.java21.data2flow.contracts.authz.ApiScope;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * MCP 읽기 도구(버전 v1, design/api/AIA-api.md §3). 모든 도구는 <b>토큰 소유자의 권한과 공간 범위, 토큰 범위</b>로 core 공개 API를
 * 사용자 위임으로 부른다(BR-AIA-05). 권한 밖 공간은 core가 404·목록 제외로 숨긴다. 응답은 도구당 최대 1,000행이고 넘으면 요약과
 * 다음 커서를 준다(BR-AIA-11).
 *
 * <p>제어 명령·배포·회원·토큰 관리 도구는 없다(BR-AIA-07). 쓰기 도구({@code mcp:write}: 분석 실행 요청, 알람 확인, 규칙·플로우 초안)와
 * 에너지 도구(ENE)는 M7에서 더한다(AIA-08.03).
 */
public class McpTools {

    public static final String VERSION = "v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final CoreClient core;
    private final int maxRows;

    public McpTools(CoreClient core, int maxRows) {
        this.core = core;
        this.maxRows = maxRows;
    }

    public List<McpToolDefinition> definitions() {
        List<McpToolDefinition> out = new ArrayList<>();
        out.add(def("list_spaces", "공간 목록", "권한 안의 공간 트리(건물 > 층 > 실). 하위 기기·오프라인·알람 수 포함",
                ApiScope.READ_DEVICES, Permission.DEV_READ,
                schema(Map.of("rootId", idProp("이 공간 아래만"), "depth", intProp("깊이(기본 전체)", 1, 10)), List.of()),
                (u, a) -> list(get(u, b -> b.path("/core/spaces").queryParamIfPresent("rootId", opt(a, "rootId"))
                        .queryParamIfPresent("depth", opt(a, "depth")).queryParam("include", "counts").build()).path("response"), a)));
        out.add(def("get_space", "공간 상세", "공간 상세(유효 목표 환경 포함)", ApiScope.READ_DEVICES, Permission.DEV_READ,
                schema(Map.of("spaceId", idProp("공간 ID")), List.of("spaceId")),
                (u, a) -> {
                    long id = id(a, "spaceId");
                    return get(u, b -> b.path("/core/spaces/{id}").build(id)).path("response");
                }));
        out.add(def("list_devices", "기기 목록", "기기 목록(상태, 연결, 배터리, 공간). 페이지당 최대 100",
                ApiScope.READ_DEVICES, Permission.DEV_READ,
                schema(Map.of("spaceId", idProp("공간(하위 포함)"), "q", strProp("이름·외부 ID 검색"),
                        "status", enumProp("상태", List.of("PENDING", "ACTIVE", "INACTIVE")), "page", intProp("페이지(1부터)", 1, 100000),
                        "size", intProp("페이지 크기(최대 100)", 1, 100)), List.of()),
                (u, a) -> page(get(u, b -> b.path("/core/devices").queryParamIfPresent("spaceId", opt(a, "spaceId"))
                        .queryParamIfPresent("q", opt(a, "q")).queryParamIfPresent("status", opt(a, "status"))
                        .queryParamIfPresent("page", opt(a, "page")).queryParamIfPresent("size", opt(a, "size")).build()))));
        out.add(def("get_device", "기기 상세", "기기 상세(현재값, 상태, 배터리, 모델, 공간)", ApiScope.READ_DEVICES, Permission.DEV_READ,
                schema(Map.of("deviceId", idProp("기기 ID")), List.of("deviceId")),
                (u, a) -> {
                    long id = id(a, "deviceId");
                    return get(u, b -> b.path("/core/devices/{id}").build(id)).path("response");
                }));
        out.add(def("query_telemetry", "텔레메트리 조회", "기기 하나의 측정 시계열. 최대 1,000행, 넘으면 요약과 nextCursor",
                ApiScope.READ_TELEMETRY, Permission.TS_READ,
                schema(Map.of("deviceId", idProp("기기 ID"), "metric", strProp("측정 항목 키(예: co2)"), "from", timeProp("시작(ISO-8601)"),
                        "to", timeProp("끝(ISO-8601)"), "resolution", enumProp("해상도", List.of("auto", "raw", "1m", "1h", "1d")),
                        "cursor", strProp("이전 응답의 nextCursor")), List.of("deviceId", "metric", "from", "to")),
                (u, a) -> {
                    long device = id(a, "deviceId");
                    String metric = str(a, "metric");
                    String from = time(a, "from");
                    String to = time(a, "to");
                    cursor(a);
                    return points(get(u, b -> b.path("/core/telemetry/series").queryParam("deviceId", device).queryParam("metrics", metric)
                            .queryParam("from", from).queryParam("to", to).queryParamIfPresent("resolution", opt(a, "resolution")).build())
                            .path("response").path("series"), a);
                }));
        out.add(def("aggregate_telemetry", "텔레메트리 집계", "공간(하위 기기 집계) 또는 기기의 집계 시계열(avg/min/max/sum). 요약에 최소·최대·평균",
                ApiScope.READ_TELEMETRY, Permission.TS_READ,
                schema(Map.of("spaceId", idProp("공간 ID(spaceId 또는 deviceId)"), "deviceId", idProp("기기 ID"),
                        "metric", strProp("측정 항목 키"), "fn", enumProp("집계 함수", List.of("avg", "min", "max", "sum")),
                        "from", timeProp("시작"), "to", timeProp("끝"), "resolution", enumProp("해상도", List.of("auto", "1m", "1h", "1d")),
                        "cursor", strProp("이전 응답의 nextCursor")), List.of("metric", "from", "to")),
                this::aggregate));
        out.add(def("list_alarms", "알람 조회", "알람 목록(심각도, 상태, 공간, 발생 시각). 기본은 처리 전 알람",
                ApiScope.READ_TELEMETRY, Permission.ALARM_READ,
                schema(Map.of("spaceId", idProp("공간(하위 포함)"), "severity", enumProp("심각도", List.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO")),
                        "status", enumProp("상태", List.of("ACTIVE", "ACKNOWLEDGED", "SUPPRESSED", "CLEARED")), "from", timeProp("시작"),
                        "to", timeProp("끝"), "page", intProp("페이지", 1, 100000), "size", intProp("크기(최대 100)", 1, 100)), List.of()),
                (u, a) -> {
                    var from = optTime(a, "from");
                    var to = optTime(a, "to");
                    return page(get(u, b -> b.path("/core/alarms").queryParamIfPresent("spaceId", opt(a, "spaceId"))
                            .queryParamIfPresent("severity", opt(a, "severity")).queryParamIfPresent("status", opt(a, "status"))
                            .queryParamIfPresent("from", from).queryParamIfPresent("to", to)
                            .queryParamIfPresent("page", opt(a, "page")).queryParamIfPresent("size", opt(a, "size")).build()));
                }));
        out.add(def("list_analysis_templates", "분석 템플릿 목록", "분석 템플릿 카탈로그(대표 질문, 필요 데이터). keyword로 질문에 맞는 템플릿 찾기",
                ApiScope.READ_ANALYTICS, Permission.ANALYTICS_READ,
                schema(Map.of("category", strProp("분류"), "keyword", strProp("질문·키워드")), List.of()),
                (u, a) -> page(get(u, b -> b.path("/core/analytics/templates").queryParamIfPresent("category", opt(a, "category"))
                        .queryParamIfPresent("keyword", opt(a, "keyword")).build()))));
        out.add(def("get_analysis_result", "분석 결과", "분석 실행의 상태와 결과(요약, 표, 근거, 주의사항, 출처). 차트 점은 빼고 표는 최대 1,000행",
                ApiScope.READ_ANALYTICS, Permission.ANALYTICS_READ,
                schema(Map.of("analysisId", idProp("분석 ID"), "runId", idProp("실행 ID")), List.of("analysisId", "runId")),
                (u, a) -> {
                    long analysis = id(a, "analysisId");
                    long run = id(a, "runId");
                    return result(get(u, b -> b.path("/core/analytics/analyses/{a}/runs/{r}").build(analysis, run)).path("response"));
                }));
        out.add(def("list_flows", "플로우 목록", "자동화 플로우 목록과 상태(읽기)", ApiScope.READ_DEVICES, Permission.FLOW_READ,
                schema(Map.of("q", strProp("이름·목적 검색"), "status", strProp("상태"), "spaceId", idProp("공간"),
                        "page", intProp("페이지", 1, 100000), "size", intProp("크기(최대 100)", 1, 100)), List.of()),
                (u, a) -> page(get(u, b -> b.path("/core/flows").queryParamIfPresent("q", opt(a, "q")).queryParamIfPresent("status", opt(a, "status"))
                        .queryParamIfPresent("spaceId", opt(a, "spaceId")).queryParamIfPresent("page", opt(a, "page"))
                        .queryParamIfPresent("size", opt(a, "size")).build()))));
        out.add(def("get_flow_status", "플로우 상태", "플로우 하나의 상태(실행 버전, 초안, 제어 노드 여부)", ApiScope.READ_DEVICES, Permission.FLOW_READ,
                schema(Map.of("flowId", Map.of("type", "string", "description", "플로우 ID", "pattern", "^[0-9a-fA-F-]{1,64}$")),
                        List.of("flowId")),
                (u, a) -> {
                    String flow = flowId(a);
                    return flowStatus(get(u, b -> b.path("/core/flows/{id}").build(flow)).path("response"));
                }));
        return out;
    }

    // ───────────── 결과 모양 ─────────────

    private JsonNode get(CurrentUser user, Function<UriBuilder, URI> uri) {
        return core.getAsUser(user, uri, CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 오프셋 목록 응답에서 목록과 페이지 정보만 */
    private JsonNode page(JsonNode body) {
        ObjectNode out = JSON.createObjectNode();
        ArrayNode rows = out.putArray("rows");
        int n = 0;
        for (JsonNode r : body.path("responses")) {
            if (n++ == maxRows) {
                break;
            }
            rows.add(r);
        }
        out.set("page", body.path("page"));
        out.set("totalPages", body.path("totalPages"));
        out.set("totalCount", body.path("totalCount"));
        return out;
    }

    /** 트리·배열을 최대 행 수로 */
    private JsonNode list(JsonNode items, Map<String, Object> args) {
        ArrayNode rows = JSON.createArrayNode();
        if (items.isArray()) {
            items.forEach(rows::add);
        } else if (!items.isMissingNode()) {
            rows.add(items);
        }
        return paged(rows, cursor(args), false);
    }

    private JsonNode points(JsonNode series, Map<String, Object> args) {
        ArrayNode rows = JSON.createArrayNode();
        for (JsonNode s : series) {
            for (JsonNode p : s.path("points")) {
                ObjectNode row = rows.addObject();
                row.put("deviceId", s.path("deviceId").asString());
                row.put("metric", s.path("metric").asString());
                row.set("t", p.path(0));
                row.set("value", p.path(1));
                row.put("unit", s.path("unit").asString(""));
            }
        }
        return paged(rows, cursor(args), true);
    }

    private JsonNode aggregate(CurrentUser u, Map<String, Object> a) {
        boolean space = a.get("spaceId") != null;
        if (space == (a.get("deviceId") != null)) {
            throw invalid("spaceId와 deviceId 중 하나만 넣으세요");
        }
        String fn = a.get("fn") == null ? "avg" : str(a, "fn");
        String metric = str(a, "metric");
        String from = time(a, "from");
        String to = time(a, "to");
        cursor(a);
        if (space) {
            long spaceId = id(a, "spaceId");
            JsonNode r = get(u, b -> b.path("/core/telemetry/space-series").queryParam("spaceId", spaceId).queryParam("metric", metric)
                    .queryParam("func", fn).queryParam("from", from).queryParam("to", to)
                    .queryParamIfPresent("resolution", opt(a, "resolution")).build()).path("response");
            ObjectNode one = JSON.createObjectNode();
            one.put("spaceId", r.path("spaceId").asString());
            one.put("metric", r.path("metric").asString());
            one.put("unit", r.path("unit").asString(""));
            one.set("points", r.path("points"));
            ArrayNode series = JSON.createArrayNode();
            series.add(one);
            return points(series, a);
        }
        long deviceId = id(a, "deviceId");
        return points(get(u, b -> b.path("/core/telemetry/series").queryParam("deviceId", deviceId).queryParam("metrics", metric)
                .queryParam("agg", fn).queryParam("from", from).queryParam("to", to)
                .queryParamIfPresent("resolution", opt(a, "resolution")).build()).path("response").path("series"), a);
    }

    /** 결과에서 차트 점(원본 시계열)은 빼고 표는 최대 행으로 */
    private JsonNode result(JsonNode r) {
        ObjectNode out = JSON.createObjectNode();
        out.set("run", r.path("run"));
        JsonNode result = r.path("result");
        if (!result.isMissingNode() && !result.isNull()) {
            ObjectNode res = out.putObject("result");
            for (String key : List.of("summary", "evidence", "caveats", "provenance", "aiCommentaryId")) {
                if (result.has(key)) {
                    res.set(key, result.get(key));
                }
            }
            ArrayNode tables = res.putArray("tables");
            for (JsonNode t : result.path("tables")) {
                ObjectNode copy = tables.addObject();
                copy.set("id", t.path("id"));
                copy.set("title", t.path("title"));
                copy.set("columns", t.path("columns"));
                ArrayNode rows = copy.putArray("rows");
                int n = 0;
                for (JsonNode row : t.path("rows")) {
                    if (n++ == maxRows) {
                        break;
                    }
                    rows.add(row);
                }
                copy.put("totalRows", t.path("rows").size());
            }
            ArrayNode charts = res.putArray("charts");
            for (JsonNode c : result.path("charts")) {
                charts.addObject().put("id", c.path("id").asString()).put("type", c.path("type").asString()).put("title", c.path("title").asString());
            }
        }
        return out;
    }

    private static JsonNode flowStatus(JsonNode f) {
        ObjectNode out = JSON.createObjectNode();
        for (String key : List.of("flowId", "name", "kind", "status", "environment", "activeVersion", "draftVersion", "hasControlNode",
                "spaceIds", "updatedAt")) {
            if (f.has(key)) {
                out.set(key, f.get(key));
            }
        }
        return out;
    }

    /** 최대 행 수로 자르고 요약·다음 커서를 붙인다(BR-AIA-11) */
    JsonNode paged(ArrayNode rows, int offset, boolean numericSummary) {
        ObjectNode out = JSON.createObjectNode();
        ArrayNode page = out.putArray("rows");
        int end = Math.min(rows.size(), offset + maxRows);
        for (int i = offset; i < end; i++) {
            page.add(rows.get(i));
        }
        ObjectNode summary = out.putObject("summary");
        summary.put("count", rows.size());
        if (numericSummary) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            double sum = 0;
            int n = 0;
            for (JsonNode r : rows) {
                if (r.path("value").isNumber()) {
                    double v = r.path("value").asDouble();
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                    sum += v;
                    n++;
                }
            }
            if (n > 0) {
                summary.put("min", min);
                summary.put("max", max);
                summary.put("avg", Math.round(sum / n * 1000) / 1000.0);
            }
        }
        if (end < rows.size()) {
            out.put("nextCursor", Base64.getUrlEncoder().withoutPadding().encodeToString(("o=" + end).getBytes(StandardCharsets.UTF_8)));
            out.put("truncated", true);
        }
        return out;
    }

    // ───────────── 입력 ─────────────

    static int cursor(Map<String, Object> args) {
        Object c = args.get("cursor");
        if (c == null) {
            return 0;
        }
        try {
            String s = new String(Base64.getUrlDecoder().decode(c.toString()), StandardCharsets.UTF_8);
            if (!s.startsWith("o=")) {
                throw invalid("cursor 형식이 틀렸습니다");
            }
            int o = Integer.parseInt(s.substring(2));
            if (o < 0) {
                throw invalid("cursor 형식이 틀렸습니다");
            }
            return o;
        } catch (IllegalArgumentException e) {
            throw invalid("cursor 형식이 틀렸습니다");
        }
    }

    static long id(Map<String, Object> args, String name) {
        Object v = args.get(name);
        if (v == null) {
            throw invalid(name + "가 필요합니다");
        }
        String s = v.toString();
        if (!s.matches("\\d{1,19}")) {
            throw invalid(name + "는 숫자 ID여야 합니다");
        }
        return Long.parseLong(s);
    }

    static String flowId(Map<String, Object> args) {
        Object v = args.get("flowId");
        if (v == null || !v.toString().matches("[0-9a-fA-F-]{1,64}")) {
            throw invalid("flowId 형식이 틀렸습니다");
        }
        return v.toString();
    }

    static String str(Map<String, Object> args, String name) {
        Object v = args.get(name);
        if (v == null || v.toString().isBlank()) {
            throw invalid(name + "가 필요합니다");
        }
        return v.toString();
    }

    static String time(Map<String, Object> args, String name) {
        String s = str(args, name);
        try {
            return Instant.parse(s).toString();
        } catch (DateTimeParseException e) {
            throw invalid(name + "는 ISO-8601 UTC 시각이어야 합니다(예: 2026-10-03T06:00:00Z)");
        }
    }

    static java.util.Optional<Object> opt(Map<String, Object> args, String name) {
        Object v = args.get(name);
        return v == null || v.toString().isBlank() ? java.util.Optional.empty() : java.util.Optional.of(v.toString());
    }

    static java.util.Optional<Object> optTime(Map<String, Object> args, String name) {
        return args.get(name) == null ? java.util.Optional.empty() : java.util.Optional.of(time(args, name));
    }

    static McpError invalid(String message) {
        return McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS).message(message).build();
    }

    // ───────────── 스키마 ─────────────

    private static McpToolDefinition def(String name, String title, String description, ApiScope scope, Permission permission,
                                         Map<String, Object> schema, java.util.function.BiFunction<CurrentUser, Map<String, Object>, JsonNode> handler) {
        return new McpToolDefinition(name, VERSION, title, description, scope, permission, schema, handler);
    }

    static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", new java.util.TreeMap<>(properties));
        s.put("required", required);
        s.put("additionalProperties", false);
        return s;
    }

    static Map<String, Object> idProp(String description) {
        return Map.of("type", "string", "pattern", "^[0-9]{1,19}$", "description", description);
    }

    static Map<String, Object> strProp(String description) {
        return Map.of("type", "string", "maxLength", 200, "description", description);
    }

    static Map<String, Object> timeProp(String description) {
        return Map.of("type", "string", "format", "date-time", "description", description);
    }

    static Map<String, Object> intProp(String description, int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max, "description", description);
    }

    static Map<String, Object> enumProp(String description, List<String> values) {
        return Map.of("type", "string", "enum", values, "description", description);
    }
}
