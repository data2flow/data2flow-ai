package net.java21.data2flow.ai.safety;

import java.util.List;
import java.util.regex.Pattern;

/**
 * LLM 출력 필터(AIA-07.02·03). 모델이 데이터 구획의 지시에 속더라도 밖으로 나가지 않게 마지막에 지운다.
 * 이메일·전화번호·토큰 모양(장기 토큰 {@code data2flow_…}, API 키, JWT)과 시스템 정책 문장(표지 포함)을 가린다.
 */
public final class OutputFilter {

    public static final String REDACTED = "[가림]";
    private static final List<Pattern> SECRETS = List.of(
            Pattern.compile("data2flow_[A-Za-z0-9_-]{8,}"),
            Pattern.compile("sk-[A-Za-z0-9_-]{10,}"),
            Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"),
            Pattern.compile("(?i)(password|passwd|secret|api[_-]?key)\\s*[:=]\\s*\\S+"));

    private OutputFilter() {
    }

    public static String filter(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        if (out.contains(PromptAssembler.CANARY) || leaksPolicy(out)) {
            out = String.join("\n", out.lines().filter(l -> !l.contains(PromptAssembler.CANARY) && !isPolicyLine(l)).toList());
        }
        out = PiiMasker.EMAIL.matcher(out).replaceAll(REDACTED);
        out = PiiMasker.PHONE.matcher(out).replaceAll(REDACTED);
        for (Pattern p : SECRETS) {
            out = p.matcher(out).replaceAll(REDACTED);
        }
        return out;
    }

    /** 시스템 정책 문장(15자 이상 줄)이 그대로 들어 있는가 */
    static boolean leaksPolicy(String text) {
        return text.lines().anyMatch(OutputFilter::isPolicyLine);
    }

    static boolean isPolicyLine(String line) {
        String t = line.trim();
        return t.length() >= 15 && PromptAssembler.POLICY.contains(t);
    }

    /** 필터가 지울 것이 있었는가(인젝션 시도 기록용) */
    public static boolean wouldRedact(String text) {
        return text != null && !text.equals(filter(text));
    }
}
