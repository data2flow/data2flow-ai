package net.java21.data2flow.ai.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** AIA-04.01 응답에서 코드·설명 분리 */
class ScriptAssistServiceTest {

    @Test
    @DisplayName("[AIA-04.01] ```javascript 블록과 설명을 나누고, 블록이 없으면 전체를 코드로")
    void parse() {
        var d = ScriptAssistService.parse("설명 앞\n```javascript\nfunction decode(i,c){}\n```\n설명 뒤");
        assertThat(d.code()).isEqualTo("function decode(i,c){}\n");
        assertThat(d.explanation()).contains("설명 앞").contains("설명 뒤");
        assertThat(ScriptAssistService.parse("```js\nx\n```").code()).isEqualTo("x\n");
        assertThat(ScriptAssistService.parse("function f(){}").code()).isEqualTo("function f(){}\n");
        assertThat(ScriptAssistService.parse(null).code()).isEmpty();
    }
}
