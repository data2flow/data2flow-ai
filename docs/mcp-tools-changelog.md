# MCP 도구 변경 내역

이 문서는 data2flow MCP 서버(`https://data2flow-mcp.java21.net/mcp`)가 제공하는 도구의 버전별 변경 내역입니다. MCP 클라이언트(Claude 등)를 연결하는 사용자와 도구를 고치는 개발자가 읽습니다. 도구 이름·입력 스키마·출력 모양이 바뀌면 도구 버전을 올리고 여기에 항목을 더해야 하며, 빠지면 계약 시험(`McpToolVersionContractTest`, TC-AIA-086)이 실패합니다(AIA-08.05).

## v1 (2026-10-05, M6)

처음 공개한 읽기 도구 11개입니다. 모두 토큰 소유자의 권한·공간 범위와 토큰 범위로만 조회하고, 결과가 1,000행을 넘으면 `summary`와 `nextCursor`를 줍니다.

| 도구 | 버전 | 범위 | 내용 |
|---|---|---|---|
| list_spaces | v1 | read:devices | 공간 트리(하위 기기·오프라인·알람 수) |
| get_space | v1 | read:devices | 공간 상세(유효 목표 환경) |
| list_devices | v1 | read:devices | 기기 목록(페이지당 최대 100) |
| get_device | v1 | read:devices | 기기 상세 |
| query_telemetry | v1 | read:telemetry | 기기 측정 시계열(최대 1,000행, 커서) |
| aggregate_telemetry | v1 | read:telemetry | 공간·기기 집계 시계열(avg/min/max/sum) |
| list_alarms | v1 | read:telemetry | 알람 목록 |
| list_analysis_templates | v1 | read:analytics | 분석 템플릿 카탈로그 |
| get_analysis_result | v1 | read:analytics | 분석 실행 상태·결과(차트 점 제외) |
| list_flows | v1 | read:devices | 플로우 목록 |
| get_flow_status | v1 | read:devices | 플로우 상태 |

쓰기 도구(`mcp:write`: 분석 실행 요청, 알람 확인, 규칙·플로우 초안)와 에너지 도구는 M7에서 더합니다. 제어 명령·배포·회원·토큰 관리 도구는 만들지 않습니다(BR-AIA-07).
