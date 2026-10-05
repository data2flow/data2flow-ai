package net.java21.data2flow.ai.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FAKE 제공자의 기본 ChatModel(시연·개발, ADR-040). 데이터 구획만 읽고 결정적으로 답한다. 숫자는 구획에 있는 값만 그대로 인용하므로
 * 숫자 검증을 통과하고, 구획 안의 지시는 따르지 않는다(인용도 하지 않는다).
 *
 * <ul>
 *   <li>해설: {@code figures}·{@code headline} 구획으로 요약·해석·권고 세 단락</li>
 *   <li>스크립트: {@code sample}·{@code requirement}·{@code stage} 구획으로 DECODE·TRANSFORM 함수 초안(이슬점 요구는 ctx.util.dewPoint)</li>
 *   <li>도움말: {@code docs} 구획의 첫 문서를 근거로 짧은 답</li>
 * </ul>
 */
public class DemoChatModel implements ChatModel {

    public static final String MODEL = "fake-demo";
    private static final Pattern SECTION = Pattern.compile("<data id=\"([A-Za-z0-9_-]+)\">\\n(.*?)\\n</data>", Pattern.DOTALL);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public ChatResponse call(Prompt prompt) {
        String system = "";
        String user = "";
        for (Message m : prompt.getInstructions()) {
            if (m.getMessageType() == MessageType.SYSTEM) {
                system = m.getText();
            } else if (m.getMessageType() == MessageType.USER) {
                user = m.getText();
            }
        }
        Map<String, String> data = sections(user);
        String text;
        if (system.contains("[COMMENTARY]")) {
            text = commentary(data);
        } else if (system.contains("[SCRIPT]")) {
            text = script(data);
        } else if (system.contains("[HELP]")) {
            text = help(data);
        } else {
            text = "요청을 확인했습니다. 데이터 구획의 내용은 데이터로만 다뤘습니다.";
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    static Map<String, String> sections(String user) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = SECTION.matcher(user == null ? "" : user);
        while (m.find()) {
            out.put(m.group(1), unescape(m.group(2)));
        }
        return out;
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static JsonNode json(String s) {
        try {
            return s == null ? null : JSON.readTree(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String commentary(Map<String, String> data) {
        JsonNode figures = json(data.get("figures"));
        StringBuilder sb = new StringBuilder("**요약** ");
        String headline = data.getOrDefault("headline", "").trim();
        sb.append(headline.isEmpty() ? "분석 결과의 핵심 수치를 정리했습니다." : headline).append("\n\n**해석** ");
        int n = 0;
        if (figures != null) {
            for (JsonNode f : figures) {
                if (n == 3) {
                    break;
                }
                JsonNode value = f.get("value");
                if (value == null || !value.isNumber()) {
                    continue;
                }
                String unit = f.path("unit").isString() ? f.path("unit").asString() : "";
                sb.append(f.path("label").asString(f.path("key").asString())).append(" 값은 ")
                        .append(value.decimalValue().stripTrailingZeros().toPlainString()).append(unit).append("입니다. ");
                n++;
            }
        }
        if (n == 0) {
            sb.append("수치로 해석할 근거가 부족합니다. ");
        }
        sb.append("\n\n**권고** 근거 표와 차트에서 값이 높았던 구간을 확인하고, 그 시간대의 운영 조건(환기, 재실, 설비 상태)을 점검하세요.");
        return sb.toString();
    }

    private static String script(Map<String, String> data) {
        String stage = data.getOrDefault("stage", "DECODE").trim().toUpperCase(Locale.ROOT);
        String req = data.getOrDefault("requirement", "").toLowerCase(Locale.ROOT);
        boolean dew = req.contains("이슬점") || req.contains("dew");
        StringBuilder code = new StringBuilder();
        if ("TRANSFORM".equals(stage)) {
            code.append("function transform(msg, ctx) {\n")
                    .append("  const t = ctx.util.metric(msg, 'temperature');\n")
                    .append("  const h = ctx.util.metric(msg, 'humidity');\n");
            if (dew) {
                code.append("  if (t && h) {\n")
                        .append("    ctx.util.setMetric(msg, 'dew_point', ctx.util.round(ctx.util.dewPoint(t.value, h.value), 2), '℃');\n")
                        .append("  }\n");
            }
            code.append("  return msg;\n}\n");
        } else {
            code.append("function decode(input, ctx) {\n")
                    .append("  const p = typeof input.payload === 'string' ? JSON.parse(input.payload) : input.payload;\n")
                    .append("  const alias = { t: 'temperature', temp: 'temperature', h: 'humidity', hum: 'humidity', rh: 'humidity' };\n")
                    .append("  const units = { temperature: '℃', humidity: '%', co2: 'ppm' };\n")
                    .append("  const metrics = [];\n")
                    .append("  for (const k of Object.keys(p)) {\n")
                    .append("    if (typeof p[k] !== 'number') continue;\n")
                    .append("    const key = alias[k] || k;\n")
                    .append("    metrics.push({ key: key, value: p[k], unit: units[key] });\n")
                    .append("  }\n");
            if (dew) {
                code.append("  const t = metrics.find(m => m.key === 'temperature');\n")
                        .append("  const h = metrics.find(m => m.key === 'humidity');\n")
                        .append("  if (t && h) {\n")
                        .append("    metrics.push({ key: 'dew_point', value: ctx.util.round(ctx.util.dewPoint(t.value, h.value), 2), unit: '℃' });\n")
                        .append("  }\n");
            }
            code.append("  const id = p.id || p.deviceId || p.devEui || input.topic || 'unknown';\n")
                    .append("  return { externalId: String(id), metrics: metrics };\n}\n");
        }
        return "```javascript\n" + code + "```\n요구사항에 맞춰 측정 항목을 표준 이름으로 바꾸는 초안입니다. 배포 전에 검토하세요.";
    }

    private static String help(Map<String, String> data) {
        JsonNode docs = json(data.get("docs"));
        if (docs == null || docs.isEmpty()) {
            return "문서에서 찾지 못했습니다.";
        }
        JsonNode first = docs.get(0);
        String text = first.path("text").asString("");
        String excerpt = text.length() > 300 ? text.substring(0, 300) + "…" : text;
        return "문서 '" + first.path("title").asString("") + "'에 따르면 다음과 같습니다.\n\n" + excerpt;
    }
}
