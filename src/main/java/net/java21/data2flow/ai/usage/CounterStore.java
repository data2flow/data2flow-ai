package net.java21.data2flow.ai.usage;

import java.time.Instant;
import java.util.List;

/**
 * 원자 카운터(사용량 한도 AIA-07.04, MCP 호출 한도 AIA-08.04). 운영은 Redis(ADR-022: 사라져도 되는 값), 시험·장애 시 메모리.
 */
public interface CounterStore {

    /**
     * 한도 안이면 모든 키를 1씩 올리고 -1을, 넘은 키가 있으면 그 키의 순번을 돌려준다(아무것도 올리지 않음).
     *
     * @param keys      카운터 키
     * @param limits    키마다 한도(같은 길이)
     * @param guardKeys 올리지 않고 한도만 보는 키(예: 오늘 쓴 토큰)
     * @param guards    guardKeys의 한도
     * @param expireAt  모든 키의 만료 시각
     */
    int acquire(List<String> keys, List<Long> limits, List<String> guardKeys, List<Long> guards, Instant expireAt);

    /** 더하고 결과를 돌려준다 */
    long add(String key, long delta, Instant expireAt);

    long get(String key);
}
