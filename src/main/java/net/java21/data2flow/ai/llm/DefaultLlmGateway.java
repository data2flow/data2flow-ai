package net.java21.data2flow.ai.llm;

import net.java21.data2flow.ai.common.AiErrorCode;
import net.java21.data2flow.ai.settings.AiSettings;
import net.java21.data2flow.ai.settings.AiSettingsService;
import net.java21.data2flow.ai.usage.UsageLimiter;
import net.java21.data2flow.ai.usage.UsageRepository;
import net.java21.data2flow.contracts.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link LlmGateway} 구현. 조직 설정 → 한도 → 호출 → 사용량·프롬프트 기록(AIA-07.04·05·06).
 * 제공자는 요청마다 설정에서 고르므로, 설정을 바꾸면 다음 요청부터 새 제공자를 쓰고 진행 중 요청은 처음 제공자로 끝난다(TC-AIA-068).
 */
public class DefaultLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultLlmGateway.class);
    private final AiSettingsService settings;
    private final LlmInvoker invoker;
    private final UsageLimiter limiter;
    private final UsageRepository usage;
    private final Map<String, String> pricePerMillion;
    private final Clock clock;

    public DefaultLlmGateway(AiSettingsService settings, LlmInvoker invoker, UsageLimiter limiter, UsageRepository usage,
                             Map<String, String> pricePerMillion, Clock clock) {
        this.settings = settings;
        this.invoker = invoker;
        this.limiter = limiter;
        this.usage = usage;
        this.pricePerMillion = pricePerMillion;
        this.clock = clock;
    }

    @Override
    public boolean available(long organizationId) {
        AiSettings s = settings.effective(organizationId);
        return s.enabled() && invoker.available(s.provider());
    }

    @Override
    public LlmResult complete(LlmRequest request) {
        AiSettings s = settings.requireEnabled(request.organizationId());
        if (!invoker.available(s.provider())) {
            throw new BusinessException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        try {
            limiter.acquire(s, request.userId());
        } catch (BusinessException e) {
            record(request, s, 0, 0, "LIMITED", null, null, 0);
            throw e;
        }
        LlmInvoker.Invocation inv;
        try {
            inv = invoker.invoke(s.provider(), s.model(), request);
        } catch (BusinessException e) {
            record(request, s, 0, 0, "ERROR", LlmInvoker.assemble(request).combined(), null, 0);
            throw e;
        }
        LlmResult r = inv.result();
        limiter.addTokens(request.organizationId(), (long) r.tokensIn() + r.tokensOut());
        record(request, s, r.tokensIn(), r.tokensOut(), "OK", inv.prompt().combined(), r.text(), r.latencyMs());
        return r;
    }

    /** 검증 뒤 내보내야 하는 기능이 많아 완성 응답을 조각으로 나눠 흘린다(마지막 조각에 합계) */
    @Override
    public Flux<LlmChunk> stream(LlmRequest request) {
        return Flux.defer(() -> {
            LlmResult r = complete(request);
            List<LlmChunk> chunks = new ArrayList<>();
            for (String part : split(r.text(), 80)) {
                chunks.add(new LlmChunk(part, false, null));
            }
            chunks.add(new LlmChunk("", true, r));
            return Flux.fromIterable(chunks);
        });
    }

    static List<String> split(String text, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return out;
    }

    private void record(LlmRequest request, AiSettings s, int in, int out, String status, String prompt, String response, long latency) {
        try {
            UsageRepository.UsageRecord rec = new UsageRepository.UsageRecord(request.organizationId(), clock.instant(), request.userId(),
                    request.feature().name(), s.provider().name(), s.model(), in, out, cost(s.model(), in, out), status);
            usage.insertUsage(rec);
            if (prompt != null) {
                usage.insertPromptLog(rec, prompt, response, null, latency);
            }
        } catch (RuntimeException e) {
            // 기록 실패가 답변을 막지 않는다
            log.warn("AI 사용량 기록 실패", e);
        }
    }

    BigDecimal cost(String model, int in, int out) {
        String price = pricePerMillion.get(model);
        if (price == null || !price.contains(":")) {
            return null;
        }
        String[] p = price.split(":");
        BigDecimal million = BigDecimal.valueOf(1_000_000);
        return new BigDecimal(p[0].trim()).multiply(BigDecimal.valueOf(in)).divide(million, 6, RoundingMode.HALF_UP)
                .add(new BigDecimal(p[1].trim()).multiply(BigDecimal.valueOf(out)).divide(million, 6, RoundingMode.HALF_UP));
    }
}
