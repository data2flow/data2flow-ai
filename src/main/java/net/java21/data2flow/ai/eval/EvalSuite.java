package net.java21.data2flow.ai.eval;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 평가 셋(AIA-07.07). 배포본의 {@code classpath:evals/commentary.yaml}(해설 50건)과 {@code evals/injection/*.yaml}
 * (프롬프트 인젝션 코퍼스 80건 이상)을 읽는다. 새 공격 사례를 발견하면 코퍼스에 먼저 더한다.
 */
public final class EvalSuite {

    public static final String NAME = "aia-default";
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private EvalSuite() {
    }

    public static List<EvalCase> load() {
        List<EvalCase> out = new ArrayList<>();
        out.addAll(read("classpath:evals/commentary.yaml"));
        out.addAll(read("classpath:evals/injection/*.yaml"));
        return out;
    }

    public static List<EvalCase> read(String pattern) {
        List<EvalCase> out = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            Arrays.sort(resources, Comparator.comparing(Resource::getFilename));
            for (Resource r : resources) {
                try (InputStream in = r.getInputStream()) {
                    JsonNode root = YAML.readTree(in);
                    String kind = root.path("kind").asString("COMMENTARY");
                    String category = root.path("category").asString(null);
                    for (JsonNode c : root.path("cases")) {
                        EvalCase e = YAML.treeToValue(c, EvalCase.class);
                        out.add(new EvalCase(e.caseId(), e.kind() == null ? kind : e.kind(), e.question(), e.expectedNumbers(),
                                e.expectedRefusal(), Boolean.TRUE.equals(e.injection()) || "INJECTION".equals(kind), e.category() == null ? category : e.category(),
                                e.field(), e.attack(), e.marker(), e.fixture()));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("평가 셋을 읽지 못했습니다: " + pattern, e);
        }
        return out;
    }
}
