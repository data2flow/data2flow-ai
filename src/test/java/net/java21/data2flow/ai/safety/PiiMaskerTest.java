package net.java21.data2flow.ai.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** AIA-07.03 개인정보 가명 처리(BR-AIA-04) — TC-AIA-060 */
class PiiMaskerTest {

    @ParameterizedTest(name = "[AIA-07.03][TC-AIA-060] \"{0}\"")
    @DisplayName("[AIA-07.03][TC-AIA-060] 이메일·전화번호(010, +82, 지역 번호)는 LLM 요청 전에 가명")
    @ValueSource(strings = {
            "연락처 kim@example.com", "KIM.Lee+ops@corp.co.kr 로 보내", "a_b-c@sub.domain.org", "x@y.io", "메일:ops@data2flow.java21.net",
            "010-1234-5678", "01012345678", "010 1234 5678", "010.1234.5678", "+82 10-1234-5678", "+82-10-1234-5678", "+821012345678",
            "02-123-4567", "031-1234-5678", "070-1234-5678", "011-123-4567", "016 123 4567",
            "기기 이름 센서(kim@corp.com)", "담당 010-9999-0000 김씨", "전화 +82 2 1234 5678"})
    void masks(String text) {
        PiiMasker.Session s = PiiMasker.session();
        String masked = s.maskText(text);
        assertThat(PiiMasker.countPii(masked)).isZero();
        assertThat(masked).containsAnyOf("이메일#", "전화#");
        assertThat(s.maskedCount()).isPositive();
    }

    @ParameterizedTest(name = "[AIA-07.03][TC-AIA-060] 그대로: \"{0}\"")
    @DisplayName("[AIA-07.03][TC-AIA-060] 측정값·시각·ID는 바꾸지 않는다")
    @ValueSource(strings = {"CO2 1240ppm", "2026-10-03T06:00:00Z", "기기 1234567", "온도 21.5", "anomaly-detect@1.2.0"})
    void keeps(String text) {
        assertThat(PiiMasker.session().maskText(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("[AIA-07.03][TC-AIA-060] 사람 객체 이름은 사용자#id, 같은 값은 같은 가명, 응답 표시용으로 이름만 되돌린다")
    void jsonPeople() {
        JsonNode in = JsonMapper.builder().build().readTree("""
                {"owner":{"userId":12,"name":"김철수"},"ackedBy":{"id":"7","name":"이영희"},"rows":[{"deviceName":"센서(kim@x.com)"},"kim@x.com"],
                 "assignee":{"name":"무명"}}
                """);
        PiiMasker.Session s = PiiMasker.session();
        JsonNode out = s.maskJson(in);
        assertThat(out.path("owner").path("name").asString()).isEqualTo("사용자#12");
        assertThat(out.path("ackedBy").path("name").asString()).isEqualTo("사용자#7");
        assertThat(out.path("assignee").path("name").asString()).isEqualTo("사용자#?");
        assertThat(out.path("rows").get(0).path("deviceName").asString()).isEqualTo("센서(이메일#1)");
        assertThat(out.path("rows").get(1).asString()).isEqualTo("이메일#1");
        assertThat(in.path("owner").path("name").asString()).isEqualTo("김철수");   // 원본은 그대로
        assertThat(s.restoreNames("사용자#12가 확인했고 이메일#1")).isEqualTo("김철수가 확인했고 이메일#1");
        assertThat(s.maskJson(null)).isNull();
        assertThat(s.maskText(null)).isNull();
        assertThat(PiiMasker.countPii(null)).isZero();
    }
}
