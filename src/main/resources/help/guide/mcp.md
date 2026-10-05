---
title: MCP로 AI 클라이언트 연결
source: USER_GUIDE
url: /help/guide/mcp
---
## MCP 연결은 어떻게 하나요
[AI > MCP 연결]에서 [토큰 발급]을 누르고 이름, 범위(read:telemetry, read:devices, read:analytics), 만료일을 정합니다. 토큰은 한 번만 보이니 바로 AI 클라이언트에 등록하세요. 엔드포인트는 https://data2flow-mcp.java21.net/mcp 입니다.

## 호출 한도
토큰 하나로 분당 60번까지 부를 수 있습니다. 넘으면 잠시 기다렸다가 다시 부르세요.
