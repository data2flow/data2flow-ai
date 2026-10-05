package net.java21.data2flow.ai.llm;

/**
 * LLM 제공자(AIA-07.05, API-AIA-07 {@code provider}). {@code NONE}은 키가 없을 때의 기본값(수치 요약 템플릿으로 대체),
 * {@code FAKE}는 개발·시험·시연용 결정적 구현이다(ADR-040). 나머지는 Spring AI 제공자 어댑터이고 키가 있어야 켜진다.
 */
public enum LlmProvider {
    NONE,
    FAKE,
    ANTHROPIC,
    OPENAI,
    GOOGLE,
    OLLAMA
}
