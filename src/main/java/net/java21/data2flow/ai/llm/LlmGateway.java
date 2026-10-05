package net.java21.data2flow.ai.llm;

import reactor.core.publisher.Flux;

/**
 * LLM 파사드(design/external-integrations.md §3.4, ADR-040). 해설·스크립트 도우미·도움말은 이 창구만 부르고 제공자 이름을 모른다.
 *
 * <p>게이트웨이가 하는 일: 조직 AI 설정 확인(꺼짐 → {@code AI_DISABLED}), 제공자 선택, 사용량 한도(→ {@code AI_QUOTA_EXCEEDED}),
 * 데이터 구획 조립과 개인정보 가명 처리(AIA-07.02·03), 시간 제한·오류 변환(→ {@code AI_PROVIDER_UNAVAILABLE}),
 * 출력 필터, 사용량·프롬프트 기록(AIA-07.06).
 *
 * <p>설계 문서의 {@code available()}은 조직마다 설정이 다르므로 조직 ID를 받는다.
 */
public interface LlmGateway {

    /** 이 조직에서 지금 LLM을 쓸 수 있는가(AI 켜짐 + 제공자 사용 가능). 한도는 보지 않는다 */
    boolean available(long organizationId);

    LlmResult complete(LlmRequest request);

    Flux<LlmChunk> stream(LlmRequest request);
}
