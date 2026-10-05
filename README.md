# data2flow-ai

Spring AI 서비스: 분석 결과 해설, 스크립트 작성 도우미, 제품 도움말, MCP 서버, AI 안전장치·운영(사용량·평가). 이 문서는 ai를 고치거나 배포하는 개발자가 읽습니다. 다 읽으면 서비스를 빌드·실행하고, LLM 제공자를 켜고, MCP 도구를 고칠 때 지킬 규칙을 알 수 있습니다.

- 관련 스펙: AIA (정본은 비공개 저장소 `data2flow-docs`의 `spec/AIA-ai-assistant.md`, `design/api/AIA-api.md`)
- 패키지: `net.java21.data2flow.ai` · Spring Boot 4.1.1 · Spring AI 2.0.1(Apache-2.0) · MCP Java SDK 2.0.0(MIT) · Java 21 · Maven Wrapper
- 포트: API 8080, actuator 8081(프로브·지표 전용) · 스키마 `data2flow_ai`(Flyway, staging만 migrate — ADR-030)

## 빌드와 실행

```bash
./mvnw verify                 # 단위·통합 테스트(Testcontainers pgvector·Redis, MockWebServer) + 커버리지 80% 검사
./mvnw spring-boot:run        # 로컬 실행(프로필 local, LLM은 FAKE)
```

공통 라이브러리 `data2flow-contracts`는 GitHub Packages에 있어서 읽기에도 토큰이 필요합니다. `~/.m2/settings.xml`에 서버 `github`(사용자 이름 + `read:packages` 권한 토큰)를 넣거나, `data2flow-contracts`를 받아 `./mvnw install`로 로컬 저장소에 설치합니다.

## M6에서 제공하는 것

| 스펙 | API·기능 |
|---|---|
| AIA-01.01~03, 07.01 | `POST/GET /ai/commentaries`(SSE `delta`→`verification`→`done`). 결과의 확정 수치만 보내고, 문장 숫자를 대조해 다르면 1회 재생성 → 그래도 다르면 `UNVERIFIED`. 숫자마다 근거 링크(`#result-table-…`) |
| AIA-04.01~03 | `POST /ai/script-assists`(초안 → pipeline 정적 검사 API-SCR-30 → 시험 실행 API-SCR-31, [고쳐 줘] 5회), 내부 `POST /internal/ai/script-drafts`(API-SCR-16 위임). 저장·배포는 하지 않는다 |
| AIA-07.02·03 | 데이터 구획(`<data>`)·이스케이프·NFKC, 개인정보 가명(사용자#12·이메일#1·전화#1), 출력 필터. 인젝션 코퍼스 84건(`src/main/resources/evals/injection`) |
| AIA-07.04·06 | 조직·사용자 일일 요청 수·토큰 한도(Redis 원자 카운터, 조직 시간대 자정 초기화), `GET /ai/usage`, `/ai/usage/me`, 프롬프트·응답 기록과 보관 기간 정리 |
| AIA-07.05 | `GET/PUT /ai/settings`, LLM 파사드 `LlmGateway`(제공자 NONE·FAKE·ANTHROPIC, OPENAI·GOOGLE·OLLAMA는 "준비 중") |
| AIA-07.07 | `GET /ai/evals/cases`, `POST/GET /ai/evals/runs`, 평가 하네스(해설 50 + 인젝션 84). 모델을 바꿀 때 평가 기준 미달이면 409 |
| AIA-08.01·04 | MCP 서버 `/mcp`(무상태 Streamable HTTP), 읽기 도구 11개(v1), 토큰 범위·공간 범위, 토큰당 분당 60회, `GET /ai/mcp/tools` |
| AIA-09.01 | 도움말 대화 `POST /ai/conversations`(`mode: HELP`), 제품 문서 색인(pgvector `help_chunks`) |

## LLM 제공자 켜기(ADR-040)

키가 없는 지금은 staging이 FAKE(결정적 시연용), prod가 NONE(수치 요약 템플릿으로 대체)입니다. 실제 제공자는 이렇게 켭니다.

1. k8s Secret `data2flow-ai-llm`에 `anthropic-api-key`를 넣고 파드를 다시 띄웁니다(→ 환경변수 `DATA2FLOW_AI_ANTHROPIC_API_KEY`). 기본 모델은 `claude-opus-5-5`입니다.
2. 같은 키로 야간 평가를 돌립니다: `DATA2FLOW_AI_ANTHROPIC_API_KEY=… ./mvnw verify -Dit.test=LlmEvalNightlyIT` (해설 50건 불일치율 ≤ 2%, 인젝션 성공 0건). 또는 화면의 평가 실행(API-AIA-16)으로 평가 기록을 남깁니다.
3. 관리자가 AI 설정(API-AIA-07)에서 제공자를 ANTHROPIC으로 바꿉니다. 그 모델의 최근 평가 정확도가 기준(기본 0.9) 미만이면 409 `AI_EVAL_BELOW_THRESHOLD`입니다.

다른 제공자는 `ChatModelProvider` 구현 하나(Spring AI 해당 모듈)와 MockWebServer 계약 시험(`LlmProviderContractTest`)을 더하면 됩니다.

## MCP 도구를 고칠 때

도구 정의(이름·설명·범위·입력 스키마)를 바꾸면 도구 버전을 올리고 `docs/mcp-tools-changelog.md`에 줄을 더한 뒤 `src/test/resources/mcp-tools.snapshot.json`을 `target/mcp-tools.snapshot.new.json`으로 바꿉니다. 빠지면 `McpToolVersionContractTest`(TC-AIA-086)가 실패합니다. 제어 명령·배포·회원·토큰 관리 도구는 만들지 않습니다(BR-AIA-07).

## 작업 규칙

스펙 ID에서 시작하고(인수 테스트 → 테스트 케이스 → 구현), 브랜치·PR·테스트 이름에 스펙 ID를 남깁니다. 1.0 전에는 `main` + `feat/<스펙ID>-<요약>`, 1.0 뒤에는 버전 브랜치 `feature/vX.Y`를 씁니다(ADR-039). PR 단계의 AI 시험은 가짜 모델(`FakeChatModel`)로만 돌리고 실제 LLM·s3·s4에는 붙지 않습니다.
