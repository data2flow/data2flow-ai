package net.java21.data2flow.ai.help;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * 키 없이 쓰는 1024차원 임베딩(특징 해싱, {@code embedding_model = hashing-1024}). 영문 낱말·오류 코드는 통째로, 한글은 글자 2개씩
 * 잘라 해시 칸에 더하고 길이를 1로 맞춘다. 임베딩 모델 키가 생기면 같은 차원의 실제 모델로 다시 색인한다(AIA-09.01).
 */
public final class HashingEmbedder {

    public static final int DIMENSIONS = 1024;
    public static final String MODEL = "hashing-1024";
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_]+|\\d+|[가-힣]+");

    private HashingEmbedder() {
    }

    public static float[] embed(String text) {
        float[] v = new float[DIMENSIONS];
        for (String token : tokens(text)) {
            CRC32 crc = new CRC32();
            crc.update(token.getBytes(StandardCharsets.UTF_8));
            long h = crc.getValue();
            int idx = (int) (h % DIMENSIONS);
            v[idx] += ((h >> 11) & 1) == 0 ? 1f : -1f;
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm > 0) {
            float n = (float) Math.sqrt(norm);
            for (int i = 0; i < v.length; i++) {
                v[i] /= n;
            }
        }
        return v;
    }

    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = WORD.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        while (m.find()) {
            String w = m.group();
            if (w.charAt(0) >= '가' && w.charAt(0) <= '힣') {
                if (w.length() == 1) {
                    out.add(w);
                }
                for (int i = 0; i + 1 < w.length(); i++) {
                    out.add(w.substring(i, i + 2));
                }
            } else {
                out.add(w.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** pgvector 리터럴 {@code [0.1,0.2,…]} */
    public static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
