---
title: 스크립트 작성
source: USER_GUIDE
url: /help/guide/scripts
---
## 디코드 스크립트
데이터 소스에 연결하는 스크립트입니다. function decode(input, ctx)가 원본 메시지를 받아 externalId와 metrics 목록을 돌려줍니다.

## 변환 스크립트
기기 모델이나 기기에 연결하는 스크립트입니다. function transform(msg, ctx)가 표준 메시지를 고쳐 돌려주거나 null을 돌려 저장하지 않게 합니다. ctx.util.dewPoint로 이슬점을 계산할 수 있습니다.

## AI로 작성
편집기에서 [AI로 작성]을 누르고 원본 샘플과 요구사항을 넣으면 초안과 시험 실행 결과가 함께 나옵니다. 시험이 실패하면 [고쳐 줘]로 다시 요청할 수 있습니다. 배포는 사람이 검토한 뒤 [배포]를 눌러야 됩니다.
