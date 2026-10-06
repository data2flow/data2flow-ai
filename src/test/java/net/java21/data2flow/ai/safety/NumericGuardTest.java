package net.java21.data2flow.ai.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** AIA-07.01 숫자 검증(BR-AIA-01·02) — TC-AIA-052 */
class NumericGuardTest {

    @ParameterizedTest(name = "[AIA-07.01][TC-AIA-052] \"{0}\" → {1}")
    @DisplayName("[AIA-07.01][AT-AIA-01.1][TC-AIA-052] 숫자 추출과 정규화")
    @CsvSource(delimiter = '|', value = {
            "CO2 최고치는 1,240ppm입니다|1240",
            "이상 12건이 있었다|12",
            "최대 점수 5.2|5.2",
            "준수율 6%|6",
            "온도가 −0.5도 낮아졌다|-0.5",
            "온도가 -0.5도 낮아졌다|-0.5",
            "약 25.5℃로 유지|25.5",
            "방문자 3.1만 명|3.1",
            "에너지 1.2천 kWh|1.2",
            "비용 2억 원|2",
            "평균 0.875|0.875",
            "12 건|12",
            "합계 12,345,678|12345678",
            "점수는 5.20이었다|5.20",
            "재실 40명, 빈 시간 12시간|40;12",
            "구간 5~7회|5;7",
            "범위 5-7|5;7",
            "87.5퍼센트|87.5"})
    void extract(String text, String expected) {
        List<String> values = NumericGuard.extract(text).stream().map(f -> f.value().toPlainString()).toList();
        assertThat(values).containsExactly(expected.split(";"));
    }

    @ParameterizedTest(name = "[AIA-07.01][TC-AIA-052] 무시: \"{0}\"")
    @DisplayName("[AIA-07.01][TC-AIA-052] 날짜·시각·목록 번호·식별자 숫자는 대조하지 않는다")
    @CsvSource(delimiter = '|', value = {
            "2026-10-03에 측정",
            "2026-10-03T06:00:00Z 기준",
            "15:00부터",
            "10월 3일 기준",
            "오후 3시 측정",
            "2026년 보고",
            "1. 환기를 늘리세요",
            "CO2와 PM2.5, PM10을 봤다",
            "AIA-01 스펙",
            "사용자#12가 확인",
            "anomaly-detect@1.2.0 템플릿",
            "## 3단계"})
    void ignored(String text) {
        assertThat(NumericGuard.extract(text)).isEmpty();
    }

    @ParameterizedTest(name = "[AIA-07.01][TC-AIA-052] 입력 {0} / 문장 \"{1}\" → {2}")
    @DisplayName("[AIA-07.01][AT-AIA-01.2][TC-AIA-052] 표시 자릿수 반올림은 일치, 다른 숫자는 불일치, 비율은 백분율로도")
    @CsvSource(delimiter = '|', value = {
            "5.24|최대 5.2|true",
            "5.24|최대 5.3|false",
            "5.25|5.3입니다|true",
            "12|이상 12건|true",
            "12|이상 15건|false",
            "1240|1,240ppm|true",
            "0.875|준수율 87.5%|true",
            "0.875|준수율 88%|true",
            "0.875|준수율 86%|false",
            "31000|3.1만 명|true",
            "31400|3.1만 명|true",
            "36000|3.1만 명|false",
            "-0.5|−0.5도|true",
            "0.5|−0.5도|false",
            "40320|40,320점|true",
            "1098.2916666666667|가장 높은 칸 값은 1098.2916666666667입니다|true",
            "1098.2916666666667|가장 높은 칸 값은 1098.2916666666668입니다|false",
            "1098.2916666666667|약 1,098.3ppm|true"})
    void match(String input, String text, boolean ok) {
        NumericGuard.Verification v = NumericGuard.verify(text, Set.of(new BigDecimal(input)));
        assertThat(v.verified()).isEqualTo(ok);
        if (!ok) {
            assertThat(v.mismatches()).isNotEmpty();
            assertThat(v.status()).isEqualTo("UNVERIFIED");
        }
    }

    @Test
    @DisplayName("[AIA-07.01][TC-AIA-052] 입력 JSON의 숫자와 문자열 속 숫자를 모은다, 숫자가 없는 문장은 VERIFIED")
    void allowedFromJson() {
        var json = JsonMapper.builder().build().readTree("{\"a\":12,\"b\":[5.2,{\"c\":\"평균 1,240ppm\"}],\"d\":null}");
        Set<BigDecimal> allowed = NumericGuard.allowedFrom(json);
        for (String expected : new String[]{"12", "5.2", "1240"}) {
            assertThat(allowed).anyMatch(a -> a.compareTo(new BigDecimal(expected)) == 0);
        }
        assertThat(NumericGuard.verify("환기를 늘리세요.", allowed).verified()).isTrue();
        assertThat(NumericGuard.verify("이상 12건, 최대 5.2, 1,240ppm", allowed).checked()).isEqualTo(3);
        assertThat(NumericGuard.extract(null)).isEmpty();
    }

    @Test
    @DisplayName("[AIA-07.01][TC-AIA-052] 속성: 입력 수치를 어떤 자릿수로 반올림해 보여도 일치한다(포맷 왕복)")
    void roundTripProperty() {
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 500; i++) {
            BigDecimal x = BigDecimal.valueOf(random.nextDouble() * 10_000).setScale(random.nextInt(4), java.math.RoundingMode.HALF_UP);
            int shown = random.nextInt(x.scale() + 1);
            String text = "값은 " + x.setScale(shown, java.math.RoundingMode.HALF_UP).toPlainString() + "입니다";
            assertThat(NumericGuard.verify(text, Set.of(x)).verified()).as(text + " ← " + x).isTrue();
        }
    }
}
