package net.java21.data2flow.ai.eval;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** API-AIA-16 {@code /api/v1/ai/evals/cases}, {@code /api/v1/ai/evals/runs}(AD) */
@RestController
public class EvalController {

    private final EvalService service;

    public EvalController(EvalService service) {
        this.service = service;
    }

    @GetMapping("/ai/evals/cases")
    public ApiResponse<List<EvalService.CaseView>> cases() {
        return ApiResponse.success(service.cases());
    }

    @PostMapping("/ai/evals/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<EvalService.RunView> run(@RequestBody EvalService.RunRequest request) {
        return ApiResponse.success(service.start(request));
    }

    @GetMapping("/ai/evals/runs")
    public ListApiResponse<EvalService.RunView> runs(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(page, size);
    }
}
