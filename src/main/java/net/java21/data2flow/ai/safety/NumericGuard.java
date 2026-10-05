package net.java21.data2flow.ai.safety;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 숫자 검증(AIA-07.01, BR-AIA-01·02). LLM 문장 속 숫자를 모두 뽑아 입력 수치와 대조한다.
 *
 * <ul>
 *   <li>뽑는 모양: {@code 1,240ppm}, {@code 12건}, {@code 5.2}, {@code 6%}, {@code −0.5}, {@code 약 25.5℃}, {@code 3.1만}</li>
 *   <li>대조하지 않는 것: 날짜·시각(2026-10-03, 15:00, 10월 3일, 오후 3시, 2026년), 줄 처음 목록 번호(1. 2.),
 *       이름·식별자에 붙은 숫자(CO2, PM2.5, AIA-01, 사용자#12)</li>
 *   <li>일치: 입력 수치를 문장에 보인 자릿수로 반올림해 같으면 일치(입력 5.24 → "5.2" 통과, "5.3" 실패).
 *       비율(0~1)은 백분율(×100)로도 본다. "만·천·억"은 단위를 곱해 본다</li>
 * </ul>
 */
public final class NumericGuard {

    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9_.#/:@])([-−]?)(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d+))?(?:\\s?(만|천|억))?(?:\\s?(%|％|퍼센트))?");
    private static final List<Pattern> IGNORED = List.of(
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}(?:[T ][0-9:.]+(?:Z|[+-]\\d{2}:?\\d{2})?)?"),
            Pattern.compile("\\d{1,2}:\\d{2}(?::\\d{2})?"),
            Pattern.compile("\\d{4}\\s?년"),
            Pattern.compile("\\d{1,2}\\s?월(?:\\s?\\d{1,2}\\s?일)?"),
            Pattern.compile("(?:오전|오후)?\\s?\\d{1,2}\\s?시(?:\\s?\\d{1,2}\\s?분)?(?![가-힣])"),
            Pattern.compile("(?m)^\\s*\\d{1,2}[.)](?=\\s)"),
            Pattern.compile("(?m)^\\s*#{1,6}\\s+\\d+"));

    private NumericGuard() {
    }

    /** 문장에서 찾은 숫자 */
    public record Found(String raw, BigDecimal value, int decimals, BigDecimal scale, boolean percent, String context) {
    }

    /** 불일치 수치(API-AIA-01 {@code mismatches[{value, context}]}) */
    public record Mismatch(String value, String context) {
    }

    /** 검증 결과 */
    public record Verification(boolean verified, List<Mismatch> mismatches, int checked) {
        public String status() {
            return verified ? "VERIFIED" : "UNVERIFIED";
        }
    }

    public static List<Found> extract(String text) {
        List<Found> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        boolean[] ignored = new boolean[text.length()];
        for (Pattern p : IGNORED) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                for (int i = m.start(); i < m.end(); i++) {
                    ignored[i] = true;
                }
            }
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            int digitsStart = m.start(2);
            if (ignored[digitsStart]) {
                continue;
            }
            if (m.group(1).isEmpty() && digitsStart >= 2 && text.charAt(digitsStart - 1) == '-'
                    && Character.isLetter(text.charAt(digitsStart - 2))) {
                continue;   // AIA-01 같은 식별자
            }
            String integer = m.group(2).replace(",", "");
            String fraction = m.group(3);
            BigDecimal value = new BigDecimal(fraction == null ? integer : integer + "." + fraction);
            if (!m.group(1).isEmpty()) {
                value = value.negate();
            }
            BigDecimal scale = switch (m.group(4) == null ? "" : m.group(4)) {
                case "만" -> BigDecimal.valueOf(10_000);
                case "천" -> BigDecimal.valueOf(1_000);
                case "억" -> BigDecimal.valueOf(100_000_000);
                default -> BigDecimal.ONE;
            };
            int from = Math.max(0, m.start() - 15);
            int to = Math.min(text.length(), m.end() + 15);
            out.add(new Found(m.group().trim(), value, fraction == null ? 0 : fraction.length(), scale, m.group(5) != null,
                    text.substring(from, to).replace('\n', ' ').trim()));
        }
        return out;
    }

    /** 입력 JSON에서 대조할 수치를 모은다(숫자 값과 문자열 속 숫자) */
    public static Set<BigDecimal> allowedFrom(JsonNode... nodes) {
        Set<BigDecimal> out = new LinkedHashSet<>();
        for (JsonNode node : nodes) {
            collect(node, out);
        }
        return out;
    }

    private static void collect(JsonNode node, Set<BigDecimal> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isNumber()) {
            out.add(node.decimalValue());
        } else if (node.isString()) {
            for (Found f : extract(node.asString())) {
                out.add(f.value().multiply(f.scale()));
            }
        } else if (node.isContainer()) {
            for (JsonNode child : node) {
                collect(child, out);
            }
        }
    }

    public static Verification verify(String text, Set<BigDecimal> allowed) {
        List<Mismatch> mismatches = new ArrayList<>();
        List<Found> found = extract(text);
        for (Found f : found) {
            if (!matches(f, allowed)) {
                mismatches.add(new Mismatch(f.raw(), f.context()));
            }
        }
        return new Verification(mismatches.isEmpty(), mismatches, found.size());
    }

    public static boolean matches(Found f, Set<BigDecimal> allowed) {
        for (BigDecimal x : allowed) {
            if (close(f, x) || (x.abs().compareTo(BigDecimal.ONE) <= 0 && close(f, x.multiply(BigDecimal.valueOf(100))))) {
                return true;
            }
        }
        return false;
    }

    private static boolean close(Found f, BigDecimal candidate) {
        BigDecimal shown = candidate.divide(f.scale(), 12, RoundingMode.HALF_UP);
        return shown.setScale(f.decimals(), RoundingMode.HALF_UP).compareTo(f.value()) == 0
                || shown.setScale(f.decimals(), RoundingMode.HALF_EVEN).compareTo(f.value()) == 0;
    }
}
