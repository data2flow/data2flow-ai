package net.java21.data2flow.ai.commentary;

/** 분석 결과 픽스처(ANA-api §4.1 모양): 최근 14일 이상 12건, 최대 점수 5.2 */
public final class Fixtures {

    private Fixtures() {
    }

    public static String anomalyResult(String deviceName) {
        return """
                {"summary":{"headline":"최근 14일 중 이상 12건","level":"WARN",
                  "metrics":[{"key":"anomalies","label":"이상 건수","value":12,"unit":null,"level":"WARN"},
                             {"key":"maxScore","label":"최대 점수","value":5.2,"unit":null}]},
                 "charts":[{"id":"series","type":"line","title":"CO2","series":[{"key":"co2","data":[[1,850],[2,1240],[3,990]]}],
                            "thresholds":[{"value":3.0,"label":"기준"}]}],
                 "tables":[{"id":"anomalies","title":"이상 목록","columns":[{"key":"time","label":"시각"}],
                            "rows":[{"time":"2026-10-01T05:00:00Z","score":5.2,"deviceName":%s,"owner":{"userId":12,"name":"김철수"}},
                                    {"time":"2026-10-02T06:00:00Z","score":4.1,"deviceName":"실습실 센서"}]}],
                 "evidence":{"threshold":3.0},
                 "caveats":["이상은 평소와 다름이지 잘못됨이 아닙니다"],
                 "provenance":{"template":"anomaly-detect@1.2.0","period":{"from":"2026-09-20T00:00:00Z","to":"2026-10-04T00:00:00Z"},
                               "resolution":"raw","points":40320,"missingRate":0.012}}
                """.formatted(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(deviceName));
    }

    public static String run(String status, String result) {
        return "{\"run\":{\"runId\":\"501\",\"analysisId\":\"77\",\"status\":\"" + status + "\"},\"result\":" + (result == null ? "null" : result) + "}";
    }
}
