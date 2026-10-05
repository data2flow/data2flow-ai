package net.java21.data2flow.ai.conversation;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/** API-AIA-02(질문, SSE)·API-AIA-10(대화 관리) */
@RestController
public class ConversationController {

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @PostMapping(path = "/ai/conversations", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter create(@Valid @RequestBody ConversationDtos.AskRequest request) throws IOException {
        return stream(service.ask(null, request));
    }

    @PostMapping(path = "/ai/conversations/{conversation-id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@PathVariable("conversation-id") long conversationId, @Valid @RequestBody ConversationDtos.AskRequest request)
            throws IOException {
        return stream(service.ask(conversationId, request));
    }

    @GetMapping("/ai/conversations")
    public ListApiResponse<ConversationDtos.Summary> list(@RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size) {
        return service.list(page, size);
    }

    @GetMapping("/ai/conversations/{conversation-id}")
    public ApiResponse<ConversationDtos.Detail> get(@PathVariable("conversation-id") long conversationId) {
        return ApiResponse.success(service.get(conversationId));
    }

    @DeleteMapping("/ai/conversations/{conversation-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("conversation-id") long conversationId) {
        service.delete(conversationId);
    }

    @PostMapping("/ai/conversations/delete-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAll() {
        service.deleteAll();
    }

    private static SseEmitter stream(List<ConversationDtos.Event> events) throws IOException {
        SseEmitter emitter = new SseEmitter(30_000L);
        for (ConversationDtos.Event e : events) {
            emitter.send(SseEmitter.event().name(e.name()).data(e.data(), MediaType.APPLICATION_JSON));
        }
        emitter.complete();
        return emitter;
    }
}
