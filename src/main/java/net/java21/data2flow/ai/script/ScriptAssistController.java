package net.java21.data2flow.ai.script;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스크립트 작성 도우미.
 * <ul>
 *   <li>API-AIA-03 {@code POST /api/v1/ai/script-assists} → {@code /ai/script-assists}(I 이상)</li>
 *   <li>내부 {@code POST /internal/ai/script-drafts}(API-SCR-16 위임, 호출자 core-api)</li>
 * </ul>
 */
@RestController
public class ScriptAssistController {

    private final ScriptAssistService service;

    public ScriptAssistController(ScriptAssistService service) {
        this.service = service;
    }

    @PostMapping("/ai/script-assists")
    public ApiResponse<ScriptAssistDtos.AssistResponse> assist(@Valid @RequestBody ScriptAssistDtos.AssistRequest request) {
        return ApiResponse.success(service.assist(request));
    }

    @PostMapping("/internal/ai/script-drafts")
    public ApiResponse<ScriptAssistDtos.DraftResponse> draft(@Valid @RequestBody ScriptAssistDtos.DraftRequest request) {
        return ApiResponse.success(service.draft(request));
    }
}
