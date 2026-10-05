package net.java21.data2flow.ai.usage;

import net.java21.data2flow.contracts.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** API-AIA-08 {@code GET /api/v1/ai/usage}(AD) · {@code GET /api/v1/ai/usage/me}(V 이상) */
@RestController
public class UsageController {

    private final UsageService service;

    public UsageController(UsageService service) {
        this.service = service;
    }

    @GetMapping("/ai/usage")
    public ApiResponse<UsageService.Usage> usage(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                                 @RequestParam(required = false) String groupBy) {
        return ApiResponse.success(service.organization(from, to, groupBy));
    }

    @GetMapping("/ai/usage/me")
    public ApiResponse<UsageService.Usage> mine(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                                @RequestParam(required = false) String groupBy) {
        return ApiResponse.success(service.mine(from, to, groupBy));
    }
}
