package net.java21.data2flow.ai.commentary;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API-AIA-01 {@code POST /api/v1/ai/commentaries}(SSE) · {@code GET /api/v1/ai/commentaries?subjectType=&subjectId=}.
 *
 * <p>수치 검증을 통과한(또는 재생성 뒤 UNVERIFIED로 확정된) 문장만 내보내므로, 생성·검증을 마친 뒤 {@code delta} 조각,
 * {@code verification}, {@code done} 순서로 흘린다. 스트림 시작 전 오류(권한·한도·제공자)는 일반 JSON 오류다.
 */
@RestController
public class CommentaryController {

    static final int CHUNK = 80;
    private final CommentaryService service;

    public CommentaryController(CommentaryService service) {
        this.service = service;
    }

    @PostMapping(path = "/ai/commentaries", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter create(@Valid @RequestBody CommentaryRequest request) throws IOException {
        CommentaryService.Generated g = service.create(request);
        SseEmitter emitter = new SseEmitter(30_000L);
        String text = g.commentary().contentMd();
        for (int i = 0; i < text.length(); i += CHUNK) {
            emitter.send(SseEmitter.event().name("delta").data(Map.of("text", text.substring(i, Math.min(text.length(), i + CHUNK))),
                    MediaType.APPLICATION_JSON));
        }
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("status", g.verification().status());
        verification.put("mismatches", g.verification().mismatches());
        emitter.send(SseEmitter.event().name("verification").data(verification, MediaType.APPLICATION_JSON));
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("commentaryId", g.commentary().commentaryId());
        done.put("model", g.commentary().model());
        done.put("tokensIn", g.tokensIn());
        done.put("tokensOut", g.tokensOut());
        done.put("citations", g.citations());
        emitter.send(SseEmitter.event().name("done").data(done, MediaType.APPLICATION_JSON));
        emitter.complete();
        return emitter;
    }

    @GetMapping("/ai/commentaries")
    public ListApiResponse<Commentary> list(@RequestParam String subjectType, @RequestParam long subjectId) {
        List<Commentary> items = service.list(subjectType, subjectId);
        return ListApiResponse.of(PageParams.of(1, Math.max(1, Math.min(PageParams.MAX_SIZE, items.size()))), items, items.size());
    }
}
