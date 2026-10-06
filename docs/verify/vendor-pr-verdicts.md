# 외주사 PR 판정 기록

기준: `templates/approval-checklist.md` (v0). 한 PR 당 한 행을 추가한다.

| PR | 판정 | 사유 |
|----|------|------|
| `review/pr-2-hardcoded-secret` (`b195e6d`) | 반려 | 치명 1건: 채점 서버 계정 비밀번호가 코드에 리터럴로 들어 있다(`modern/api/src/main/java/com/example/assignment/GradingServerClient.java:28-29`). 수정 방향은 `application.yml` 의 `${…}` 환경변수 주입(`@Value`)이다. 테스트는 56건 실행, 실패 0건으로 통과했다. 요청 문장이 PR 에 없어 커밋 메시지 · 코드 주석으로 "외부 채점 서버 연동 클라이언트 추가"라고 추정했고, 변경 2개 파일은 모두 이 범위 안이다(추정이므로 확인 필요). 경고: 요청 본문을 JSON 문자열 이어 붙이기로 만들어 `studentId` 가 이스케이프되지 않는다(`GradingServerClient.java:56`). `requestGrading` 은 테스트가 없다(`GradingServerClientTest.java` 는 `buildRequest` 만 검증). |
