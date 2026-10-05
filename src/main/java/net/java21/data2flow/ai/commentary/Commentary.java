package net.java21.data2flow.ai.commentary;

import net.java21.data2flow.ai.safety.NumericGuard;

import java.time.Instant;
import java.util.List;

/**
 * 해설(data2flow_ai.commentaries, API-AIA-01 응답 항목). 상태: GENERATING → VERIFIED | UNVERIFIED | FAILED.
 */
public record Commentary(String commentaryId, String subjectType, String subjectId, String status, String contentMd, String model,
                         List<NumericGuard.Mismatch> mismatches, String supersededBy, Instant createdAt) {
}
