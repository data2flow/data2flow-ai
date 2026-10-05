package net.java21.data2flow.ai.common;

import net.java21.data2flow.ai.llm.LlmProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * data2flow-ai 설정({@code data2flow.ai.*}). 비밀값(LLM 키)은 환경변수·k8s Secret으로만 받는다.
 *
 * @param flywayMode   {@code migrate}(staging·시험) 또는 {@code validate}(prod·local), ADR-030
 * @param coreUri      core-api 내부 주소(권한 판정·감사·사용자 위임 조회)
 * @param analyticsUri analytics 내부 주소(실행 ID로 분석 ID 찾기, 자동 해설)
 * @param pipelineUri  pipeline 내부 주소(스크립트 정적 검사·테스트 실행, API-SCR-30·31)
 * @param webBaseUrl   화면 주소(근거 링크·도움말 링크)
 * @param zone         조직 시간대 기본값(사용량 한도 자정 초기화, BR-AIA-08)
 * @param llm          LLM 제공자(ADR-040)
 * @param mcp          MCP 서버(AIA-08)
 * @param events       EVT-ANA-01 소비(자동 해설)
 * @param retention    기록 정리 작업(BR-AIA-15)
 */
@ConfigurationProperties(prefix = "data2flow.ai")
public record AiProperties(String flywayMode, String coreUri, String analyticsUri, String pipelineUri, String webBaseUrl, ZoneId zone,
                           Llm llm, Mcp mcp, Events events, Retention retention) {

    @ConstructorBinding
    public AiProperties {
        flywayMode = blank(flywayMode) ? "validate" : flywayMode;
        coreUri = blank(coreUri) ? "http://data2flow-core-api" : coreUri;
        analyticsUri = blank(analyticsUri) ? "http://data2flow-analytics" : analyticsUri;
        pipelineUri = blank(pipelineUri) ? "http://data2flow-pipeline" : pipelineUri;
        webBaseUrl = blank(webBaseUrl) ? "https://data2flow.java21.net" : webBaseUrl;
        zone = zone == null ? ZoneId.of("Asia/Seoul") : zone;
        llm = llm == null ? new Llm(null, null, null, null, null, null) : llm;
        mcp = mcp == null ? new Mcp(0, 0, null) : mcp;
        events = events == null ? new Events(false) : events;
        retention = retention == null ? new Retention(true) : retention;
    }

    /**
     * LLM 제공자 설정. 키가 없으면 기본값 NONE(수치 요약 템플릿으로 대체, AIA-07.05). 개발·시험은 FAKE.
     *
     * @param defaultProvider  조직 설정 행이 없을 때 쓰는 제공자
     * @param allowedProviders 이 배포에서 고를 수 있는 제공자. prod는 FAKE를 빼서 가짜 해설이 운영에 나가지 않게 한다
     * @param timeout          제공자 호출 제한 시간(초과 → AI_PROVIDER_UNAVAILABLE)
     * @param anthropic        Anthropic 어댑터(키가 있을 때만 켜짐)
     * @param defaultModel     조직 설정 행이 없을 때의 모델 이름
     * @param pricePerMillion  모델별 추정 비용(USD, 입력/출력 100만 토큰당). 키 {@code 모델이름}, 값 {@code "입력:출력"}
     */
    public record Llm(LlmProvider defaultProvider, Set<LlmProvider> allowedProviders, Duration timeout, Anthropic anthropic,
                      String defaultModel, java.util.Map<String, String> pricePerMillion) {
        public Llm {
            defaultProvider = defaultProvider == null ? LlmProvider.NONE : defaultProvider;
            allowedProviders = allowedProviders == null || allowedProviders.isEmpty()
                    ? Set.of(LlmProvider.NONE, LlmProvider.ANTHROPIC) : Set.copyOf(allowedProviders);
            timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
            anthropic = anthropic == null ? new Anthropic(null, null, null) : anthropic;
            defaultModel = blank(defaultModel) ? Anthropic.DEFAULT_MODEL : defaultModel;
            pricePerMillion = pricePerMillion == null ? java.util.Map.of() : java.util.Map.copyOf(pricePerMillion);
        }
    }

    /**
     * Anthropic 어댑터. 키(k8s Secret {@code data2flow-ai-llm}의 {@code anthropic-api-key})가 비어 있으면 등록만 되고 "준비 중"이다.
     *
     * @param apiKey  API 키(비밀값, 환경변수 DATA2FLOW_AI_ANTHROPIC_API_KEY)
     * @param baseUrl API 주소(기본 https://api.anthropic.com, 시험은 MockWebServer)
     * @param model   기본 모델
     */
    public record Anthropic(String apiKey, String baseUrl, String model) {
        public static final String DEFAULT_MODEL = "claude-opus-5-5";

        public Anthropic {
            baseUrl = blank(baseUrl) ? "https://api.anthropic.com" : baseUrl;
            model = blank(model) ? DEFAULT_MODEL : model;
        }

        public boolean configured() {
            return !blank(apiKey);
        }

        @Override
        public String toString() {
            return "Anthropic[apiKey=" + (configured() ? "***" : "") + ", baseUrl=" + baseUrl + ", model=" + model + "]";
        }
    }

    /**
     * MCP 서버(AIA-08).
     *
     * @param callsPerMinute 토큰당 분당 호출 한도(BR-AIA-11, 기본 60)
     * @param maxRows        도구 응답 최대 행(기본 1,000)
     * @param instructions   서버 안내문(도구 버전·변경 내역 위치)
     */
    public record Mcp(int callsPerMinute, int maxRows, List<String> instructions) {
        public Mcp {
            callsPerMinute = callsPerMinute <= 0 ? 60 : callsPerMinute;
            maxRows = maxRows <= 0 ? 1000 : maxRows;
            instructions = instructions == null ? List.of() : List.copyOf(instructions);
        }
    }

    /** @param enabled EVT-ANA-01을 큐 {@code ai.events}로 받는다(기본 꺼짐, staging·prod에서 켬) */
    public record Events(boolean enabled) {
    }

    /** @param enabled 보관 기간 지난 프롬프트·응답 기록 정리와 사용량 파티션 준비(BR-AIA-15) */
    public record Retention(boolean enabled) {
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
