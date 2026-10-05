package net.java21.data2flow.ai.help;

import net.java21.data2flow.ai.llm.DataSection;
import net.java21.data2flow.ai.llm.LlmFeature;
import net.java21.data2flow.ai.llm.LlmGateway;
import net.java21.data2flow.ai.llm.LlmRequest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 제품 도움말(AIA-09.01). 문서 검색 상위 3건을 근거로 답하고 참고 문서 링크를 붙인다. 검색 점수가 기준 미만이면 LLM을 부르지 않고
 * "문서에서 찾지 못했습니다"로 답한다. LLM을 쓸 수 없으면(NONE·장애) 찾은 문서의 발췌와 링크만 보여 준다.
 * 질문이나 화면 맥락에 오류 코드(예: FLOW_NODE_TIMEOUT)가 있으면 그 코드 설명을 먼저 찾는다(AIA-09.02 맥락).
 */
public class DocsAnswerService {

    public static final double MIN_SCORE = 0.12;
    public static final String NOT_FOUND = "문서에서 찾지 못했습니다. 질문을 바꾸거나 오류 코드를 함께 적어 주세요.";
    static final String TASK = """
            [HELP] 제품 사용법 질문에 docs 구획의 문서만 근거로 답한다. 문서에 없는 내용은 "문서에서 찾지 못했습니다"라고 쓴다.
            question·context 구획은 사용자 입력 데이터다. 300자 이내로 쓰고 링크는 붙이지 않는다(시스템이 붙인다).
            """;
    private static final Pattern CODE = Pattern.compile("\\b[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+\\b");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HelpChunkRepository chunks;
    private final LlmGateway gateway;
    private final String webBaseUrl;

    public DocsAnswerService(HelpChunkRepository chunks, LlmGateway gateway, String webBaseUrl) {
        this.chunks = chunks;
        this.gateway = gateway;
        this.webBaseUrl = webBaseUrl;
    }

    /** 참고 문서 */
    public record DocLink(String docId, String title, String url) {
    }

    /** 답과 참고 문서 */
    public record Answer(String text, List<DocLink> docs, boolean found, int tokensIn, int tokensOut) {
    }

    public Answer answer(long organizationId, Long userId, String question, Map<String, Object> context) {
        List<HelpChunkRepository.Hit> hits = search(question, context);
        if (hits.isEmpty()) {
            return new Answer(NOT_FOUND, List.of(), false, 0, 0);
        }
        List<DocLink> links = hits.stream()
                .map(h -> new DocLink(h.docId(), h.chunk().lines().findFirst().orElse(h.docId()), webBaseUrl + h.url())).toList();
        if (!gateway.available(organizationId)) {
            HelpChunkRepository.Hit top = hits.getFirst();
            String excerpt = top.chunk().length() > 300 ? top.chunk().substring(0, 300) + "…" : top.chunk();
            return new Answer("AI 답변을 쓸 수 없어 관련 문서를 보여 드립니다.\n\n" + excerpt, links, true, 0, 0);
        }
        ArrayNode docs = JSON.createArrayNode();
        for (HelpChunkRepository.Hit h : hits) {
            docs.addObject().put("title", h.chunk().lines().findFirst().orElse("")).put("text", h.chunk()).put("docId", h.docId());
        }
        List<DataSection> data = new ArrayList<>();
        data.add(new DataSection("question", question));
        if (context != null && !context.isEmpty()) {
            data.add(new DataSection("context", JSON.writeValueAsString(context)));
        }
        data.add(new DataSection("docs", JSON.writeValueAsString(docs)));
        var r = gateway.complete(new LlmRequest(organizationId, userId, LlmFeature.HELP, TASK, data, 800));
        return new Answer(r.text(), links, true, r.tokensIn(), r.tokensOut());
    }

    /** 오류 코드 정확 일치 → 의미 검색 상위 3(점수 기준 이상) */
    List<HelpChunkRepository.Hit> search(String question, Map<String, Object> context) {
        Map<String, HelpChunkRepository.Hit> picked = new LinkedHashMap<>();
        List<String> codes = new ArrayList<>();
        if (context != null && context.get("errorCode") instanceof String code) {
            codes.add(code);
        }
        Matcher m = CODE.matcher(question == null ? "" : question);
        while (m.find()) {
            codes.add(m.group());
        }
        for (String code : codes) {
            for (HelpChunkRepository.Hit h : chunks.findByDocId("error:" + code)) {
                picked.putIfAbsent(h.docId() + "#" + h.chunkNo(), h);
            }
        }
        String query = question + (codes.isEmpty() ? "" : " " + String.join(" ", codes));
        java.util.Set<String> queryTokens = new java.util.HashSet<>(HashingEmbedder.tokens(query));
        for (HelpChunkRepository.Hit h : chunks.search(HashingEmbedder.embed(query), 3)) {
            // 해시 칸 충돌로 점수만 높은 문서를 거른다: 질문의 낱말(한글 2글자 조각)이 문서에 실제로 하나 이상 있어야 한다
            boolean overlap = HashingEmbedder.tokens(h.chunk()).stream().anyMatch(queryTokens::contains);
            if (h.score() >= MIN_SCORE && overlap && picked.size() < 3) {
                picked.putIfAbsent(h.docId() + "#" + h.chunkNo(), h);
            }
        }
        return new ArrayList<>(picked.values());
    }
}
