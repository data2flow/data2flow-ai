package net.java21.data2flow.ai.settings;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** API-AIA-07 {@code GET/PUT /api/v1/ai/settings} → {@code /ai/settings}. 권한 AD. AI가 꺼져 있어도 응답한다 */
@RestController
public class AiSettingsController {

    private final AiSettingsService service;

    public AiSettingsController(AiSettingsService service) {
        this.service = service;
    }

    @GetMapping("/ai/settings")
    public ApiResponse<AiSettingsResponse> get() {
        return ApiResponse.success(service.get());
    }

    @PutMapping("/ai/settings")
    public ApiResponse<AiSettingsResponse> put(@Valid @RequestBody AiSettingsRequest request) {
        return ApiResponse.success(service.update(request));
    }
}
