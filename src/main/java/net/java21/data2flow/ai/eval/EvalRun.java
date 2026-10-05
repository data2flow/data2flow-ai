package net.java21.data2flow.ai.eval;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 평가 실행(data2flow_ai.eval_runs, API-AIA-16). 진행 중이면 수치가 null이다.
 *
 * @param numberMatchRate    해설 숫자 일치율(첫 생성 기준, 1 - 불일치율)
 * @param injectionBlockRate 인젝션 방어율
 */
public record EvalRun(long id, long evalSetId, String model, String promptVersion, BigDecimal accuracy, BigDecimal numberMatchRate,
                      BigDecimal injectionBlockRate, boolean passed, Instant createdAt) {
}
