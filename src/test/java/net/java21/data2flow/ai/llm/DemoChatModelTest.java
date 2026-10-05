package net.java21.data2flow.ai.llm;

import net.java21.data2flow.ai.help.DocsAnswerService;
import net.java21.data2flow.ai.safety.PiiMasker;
import net.java21.data2flow.ai.safety.PromptAssembler;
import net.java21.data2flow.ai.script.ScriptAssistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FAKE 제공자 기본 모델(ADR-040): 데이터 구획만 읽고 결정적으로 답한다 */
class DemoChatModelTest {

    static String ask(String task, DataSection... data) {
        LlmRequest req = new LlmRequest(1, 1L, LlmFeature.CHAT, task, List.of(data), 100);
        Prompt p = new Prompt(List.of(new SystemMessage(PromptAssembler.system(req)), new UserMessage(PromptAssembler.user(req, PiiMasker.session()))));
        return new DemoChatModel().call(p).getResult().getOutput().getText();
    }

    @Test
    @DisplayName("[AIA-04.01] 스크립트: DECODE·TRANSFORM 초안, 이슬점 요구면 dewPoint")
    void script() {
        String decode = ask(ScriptAssistService.TASK, new DataSection("stage", "DECODE"), new DataSection("requirement", "이슬점 추가"));
        assertThat(decode).contains("function decode(input, ctx)").contains("dewPoint").contains("```javascript");
        String transform = ask(ScriptAssistService.TASK, new DataSection("stage", "TRANSFORM"), new DataSection("requirement", "dew point"));
        assertThat(transform).contains("function transform(msg, ctx)").contains("dew_point");
        assertThat(ask(ScriptAssistService.TASK, new DataSection("stage", "TRANSFORM"))).doesNotContain("dewPoint");
    }

    @Test
    @DisplayName("[AIA-09.01] 도움말: 첫 문서 근거, 문서가 없으면 모른다, 그 밖의 작업은 고정 답")
    void helpAndGeneric() {
        assertThat(ask(DocsAnswerService.class.getSimpleName() + " [HELP]", new DataSection("docs", "[{\"title\":\"규칙\",\"text\":\"규칙 만드는 법\"}]")))
                .contains("규칙 만드는 법");
        assertThat(ask("[HELP]", new DataSection("docs", "[]"))).contains("찾지 못했습니다");
        assertThat(ask("[HELP]", new DataSection("docs", "깨진 JSON"))).contains("찾지 못했습니다");
        assertThat(ask("기타")).contains("데이터로만");
        assertThat(ask("[COMMENTARY]", new DataSection("figures", "[{\"key\":\"x\",\"value\":\"문자\"}]"))).contains("근거가 부족");
    }
}
