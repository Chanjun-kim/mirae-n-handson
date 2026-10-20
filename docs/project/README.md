이관한 문항 검색 모듈(`GET /api/items/search`)이 레거시 스냅샷과 같은 동작인지 `characterization` 테스트로 비교하고, 변경분을 승인 체크리스트(`templates/approval-checklist.md`)로 대조해 승인 · 반려를 판정한다.
`cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` (새 API 8080 기동 후) 실행한 다음 Claude Code 에서 `/verify day4-start..HEAD` 를 입력한다.
알려진 한계는 PR 자동 리뷰 워크플로(`.github/workflows/claude-review.yml`)가 아직 커밋 · 실행되지 않았고 새 API 를 사람이 먼저 띄워야 한다는 점이며, 현업에서는 저장소 시크릿 `ANTHROPIC_API_KEY` 등록, 비교 대상 서버 주소, 사내 코딩 기준에 맞춘 체크리스트로 바꿔야 한다.
