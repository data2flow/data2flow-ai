package net.java21.data2flow.ai.safety;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 개인정보 가명 처리(AIA-07.03, BR-AIA-04). 사용자 이름·이메일·전화번호를 LLM 요청 전에 가명으로 바꾼다.
 *
 * <ul>
 *   <li>사람을 가리키는 객체({@code owner}, {@code assignee}, {@code ackedBy} … 안의 {@code {userId|id, name}})의 이름 → {@code 사용자#<id>}</li>
 *   <li>문장 속 이메일 → {@code 이메일#n}, 전화번호(010-1234-5678, +82 10 …, 02-…) → {@code 전화#n}. 기기 이름 속 이메일도 같다</li>
 * </ul>
 * 가명 → 원래 값 대응은 한 요청({@link Session}) 안에서만 갖고, 응답 표시용으로 사용자 이름만 되돌린다(이메일·전화는 되돌리지 않음).
 */
public final class PiiMasker {

    public static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** 한국 휴대·지역 번호와 +82 형식. 구분자는 -, 공백, . */
    public static final Pattern PHONE = Pattern.compile(
            "(?<![\\d])(?:\\+82[-\\s.]?|0)(?:1[016789]|2|[3-6][1-5]|70)[-\\s.]?\\d{3,4}[-\\s.]?\\d{4}(?![\\d])");
    private static final Set<String> PERSON_KEYS = Set.of("owner", "assignee", "ackedBy", "acked_by", "createdBy", "updatedBy", "user",
            "decidedBy", "approvedBy", "appliedBy", "actor", "requester", "requestedBy", "author");

    private PiiMasker() {
    }

    public static Session session() {
        return new Session();
    }

    /** 한 요청의 가명 대응 */
    public static final class Session {
        private final Map<String, String> emails = new LinkedHashMap<>();
        private final Map<String, String> phones = new LinkedHashMap<>();
        private final Map<String, String> names = new LinkedHashMap<>();

        /** 문장 속 이메일·전화번호를 가명으로 */
        public String maskText(String text) {
            if (text == null || text.isEmpty()) {
                return text;
            }
            String out = replace(EMAIL, text, emails, "이메일#");
            return replace(PHONE, out, phones, "전화#");
        }

        /** JSON 전체: 사람 객체의 이름과 모든 문자열 속 이메일·전화 */
        public JsonNode maskJson(JsonNode node) {
            if (node == null) {
                return null;
            }
            JsonNode copy = node.deepCopy();
            walk(copy, null);
            return copy;
        }

        /** 알고 있는 사용자 이름을 가명으로(예: 리포트 수신자 표시 이름) */
        public String pseudonymForUser(long userId, String name) {
            String alias = "사용자#" + userId;
            if (name != null && !name.isBlank()) {
                names.put(alias, name);
            }
            return alias;
        }

        /** 응답 표시용: 사용자 가명만 원래 이름으로 */
        public String restoreNames(String text) {
            String out = text;
            for (Map.Entry<String, String> e : names.entrySet()) {
                out = out.replace(e.getKey(), e.getValue());
            }
            return out;
        }

        public int maskedCount() {
            return emails.size() + phones.size() + names.size();
        }

        private void walk(JsonNode node, String parentKey) {
            if (node instanceof ObjectNode obj) {
                boolean person = parentKey != null && PERSON_KEYS.contains(parentKey) && obj.has("name");
                if (person) {
                    JsonNode id = obj.has("userId") ? obj.get("userId") : obj.get("id");
                    String alias = id == null || id.isNull() ? "사용자#?" : pseudonymForUser(id.asLong(), obj.get("name").asString());
                    obj.put("name", alias);
                }
                for (String key : obj.propertyNames()) {
                    JsonNode child = obj.get(key);
                    if (child.isString()) {
                        obj.set(key, StringNode.valueOf(maskText(child.asString())));
                    } else {
                        walk(child, key);
                    }
                }
            } else if (node instanceof ArrayNode arr) {
                for (int i = 0; i < arr.size(); i++) {
                    JsonNode child = arr.get(i);
                    if (child.isString()) {
                        arr.set(i, StringNode.valueOf(maskText(child.asString())));
                    } else {
                        walk(child, parentKey);
                    }
                }
            }
        }

        private static String replace(Pattern pattern, String text, Map<String, String> seen, String prefix) {
            Matcher m = pattern.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String alias = seen.computeIfAbsent(m.group(), k -> prefix + (seen.size() + 1));
                m.appendReplacement(sb, Matcher.quoteReplacement(alias));
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }

    /** 문장에 이메일·전화번호 모양이 남아 있는 개수(AIA-07.03 수용 기준 "0건" 확인용) */
    public static int countPii(String text) {
        if (text == null) {
            return 0;
        }
        int n = 0;
        Matcher m = EMAIL.matcher(text);
        while (m.find()) {
            n++;
        }
        m = PHONE.matcher(text);
        while (m.find()) {
            n++;
        }
        return n;
    }
}
