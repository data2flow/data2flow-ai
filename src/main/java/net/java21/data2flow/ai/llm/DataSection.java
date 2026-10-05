package net.java21.data2flow.ai.llm;

import java.util.Objects;

/**
 * 프롬프트의 데이터 구획(BR-AIA-03). 기기 payload·이름·태그·사용자 입력은 여기에만 넣고 시스템 지시와 섞지 않는다.
 * {@code content}는 문자열(보통 JSON)이고, 조립할 때 구분자·역할 표시를 무력화한다({@code PromptAssembler}).
 *
 * @param id      구획 이름(영문·숫자·-·_), 예: {@code figures}, {@code sample}, {@code requirement}
 * @param content 데이터
 */
public record DataSection(String id, String content) {

    public DataSection {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[A-Za-z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("구획 이름 형식: " + id);
        }
        content = content == null ? "" : content;
    }
}
