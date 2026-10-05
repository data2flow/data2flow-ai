package net.java21.data2flow.ai.llm;

/** 스트리밍 조각. 마지막 조각은 {@code done=true}이고 {@code result}에 합계가 있다 */
public record LlmChunk(String text, boolean done, LlmResult result) {
}
