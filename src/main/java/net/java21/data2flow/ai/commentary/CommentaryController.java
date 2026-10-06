package net.java21.data2flow.ai.commentary;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.web.ErrorResponse;
import net.java21.data2flow.contracts.web.GlobalExceptionHandler;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
    private final GlobalExceptionHandler errors;

    public CommentaryController(CommentaryService service, GlobalExceptionHandler errors) {
        this.service = service;
        this.errors = errors;
    }

    /**
     * 스트림 시작 전 오류는 일반 JSON 오류로 답한다. 이 경로는 {@code produces = text/event-stream}이라 공통 처리기의 JSON 본문이
     * 내용 협상에서 막혀(Accept: text/event-stream만 보내는 웹 BFF) 500이 되므로, Content-Type을 JSON으로 정해 돌려준다(M6 시연에서 발견).
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> beforeStream(BusinessException ex) {
        return asJson(errors.handleBusiness(ex));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalidBeforeStream(MethodArgumentNotValidException ex) {
        return asJson(errors.handleValidation(ex));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableBeforeStream(HttpMessageNotReadableException ex) {
        return asJson(errors.handleBadRequest(ex));
    }

    private static ResponseEntity<ErrorResponse> asJson(ResponseEntity<ErrorResponse> r) {
        return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders()).contentType(MediaType.APPLICATION_JSON).body(r.getBody());
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
