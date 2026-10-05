package net.java21.data2flow.ai.llm;

/**
 * LLM 응답(출력 필터를 지난 문장).
 *
 * @param text      응답 문장
 * @param provider  제공자
 * @param model     모델
 * @param tokensIn  입력 토큰(제공자가 주지 않으면 추정)
 * @param tokensOut 출력 토큰
 * @param latencyMs 지연
 */
public record LlmResult(String text, LlmProvider provider, String model, int tokensIn, int tokensOut, long latencyMs) {
}
