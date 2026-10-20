# 실전 프로젝트 설계 — 백엔드 / Chanjun-kim

(이름은 `git config user.name` 값이다. 다른 이름을 쓰려면 알려 주세요.)

## 1. 목표
이관한 문항 검색 모듈(`GET /api/items/search`)이 레거시 스냅샷과 같은 동작임을 `characterization` 테스트로 확인하고, 3회차에 받은 PR 에 자동 리뷰 코멘트를 1회 남긴다.

## 2. 재료
판정 기준: 파일을 열어 내용을 확인했으면 "있음". 실행해서 확인한 것은 아니다(이번 설계에서는 서버 · 테스트를 실행하지 않았다).

| 재료 | 있음/없음 | 경로 |
|---|---|---|
| Skill — 이관 검증 | 있음 | `.claude/skills/verify/SKILL.md` |
| Skill — 컨벤션 점검 | 있음 | `.claude/skills/convention-check/SKILL.md` |
| Skill — 영향도 · 문서화 · RCA · 배치 점검 | 있음 (이번 범위에서는 쓰지 않음) | `.claude/skills/{impact,document-module,rca,batch-check}/SKILL.md` |
| Sub-agent — reviewer · tester | 있음 | `.claude/agents/reviewer.md`, `.claude/agents/tester.md` |
| Sub-agent — iac-reviewer | 있음 (Terraform 용, 범위 밖) | `.claude/agents/iac-reviewer.md` |
| Hook (위험 명령 차단 · 시크릿 스캔) | 있음 | `hooks/block-dangerous.mjs`, `hooks/secret-scan.mjs`, 등록: `.claude/settings.json`, 실행기: `scripts/hook-node.sh` |
| 동작 보존 테스트 틀 | 있음 | `characterization/` (`tests/item-bank.test.js`, `lib/target.mjs`, `__snapshots__/item-bank.test.js.snap`) |
| 이관 모듈(백엔드) | 있음 | `modern/api/src/main/java/com/example/item/` (`ItemSearchController` · `ItemSearchService` · `ItemSearchRepository` · `ItemSearchCondition` 등) |
| 이관 모듈 단위 테스트 | 있음 | `modern/api/src/test/java/com/example/item/ItemSearchConditionTest.java` 외 (`modern/api/src/test/`) |
| 이관 검증 기록 | 있음 | `docs/verify/migration-review-1.md` ~ `migration-review-3.md` (3회차: 승인) |
| 레거시 모듈 문서 | 있음 | `docs/item-bank/` (ARCHITECTURE · ERD · BUSINESS-RULES · CROSS-CHECK) |
| pipeline-samples | 있음, 이 트랙과 무관 | `pipeline-samples/` (배치 로그 · Terraform 은 데이터/인프라 트랙용) |
| PR 자동 리뷰 설정 (GitHub Actions) | **없음** | `.github/` 디렉터리가 없다 → 새로 만들 것 |
| PR 자동 리뷰 설정 (Jenkins) | 있음, 예시만 | `ci-ports/Jenkinsfile` (파일 머리 주석: 실제 Jenkins 에서 실행해 보지 않은 구성안) |
| 3회차에 받은 PR 주소 | **불확실** | 저장소 문서에는 PR URL 이 없다. `gh pr list` 로 origin #1 이 보이나 3회차 PR 인지 미확인 (`github.com/.../pull/N` 검색 결과 0건). `docs/project/` 도 이번에 처음 만든다 |
| GitHub MCP 연결 | 설정은 있으나 이 세션에서 연결 실패(그대로 둠, `gh` CLI 로그인은 확인됨) | `.mcp.json`, 오류: `Authorization header is badly formatted` |

새로 만들 것: PR 자동 리뷰 워크플로(또는 Jenkinsfile 을 실제 환경에 맞춘 것), PR 주소 기록.

## 3. 범위
### 한다
1. 새 API(8080)를 대상으로 `characterization` 을 실행해 전부 통과하는 것을 확인한다.
2. GitHub Actions 자동 리뷰 워크플로를 PR(origin #1)에 한 번 돌려 코멘트 1개를 받는다.

### 하지 않는다
- `characterization/` 의 테스트 · 스냅샷 수정 — CLAUDE.md 5절이 금지하고, 기준을 바꾸면 비교의 의미가 없어진다.
- 레거시 동작이 버그로 보여도 고치기 — 이관 중에는 보존이 원칙이라 "의심 동작" 목록으로만 보고한다.
- `assignment` · `grade` 모듈 이관 — 완성 기준에 없고 레거시(8082/8083)가 꺼져 있다.
- 의존성 · DB 스키마 · `application.yml` DB 접속 정보 변경 — 승인 없이 못 바꾸는 항목이다.
- `modern/web` 변경 — 백엔드 트랙이다.
- 자동 리뷰에 API 키 등 비밀값을 파일에 적기 — 저장소 시크릿으로만 주입한다.
- 실패 시 이관 코드 수정 계획을 미리 세우기 — 이미 36건 통과 · 실패 0(`RESULT.md`)이라 완성 기준 ①이 충족돼 있다. 실패가 새로 나오면 그때 고친다.
- 코멘트 내용에 대한 후속 정리(지적 사항 목록화) — 완성 기준 ②는 "코멘트 1회"이고 내용 처리는 요구하지 않는다.
- 단계를 나눈 별도의 워크플로 사전 점검 — 워크플로가 PR 에서 한 번 돌면 작성 · 시크릿 · 트리거가 함께 검증되므로 따로 확인하지 않아도 완성 기준 ②가 유지된다.

## 4. 미해결 질문
결정됨(사용자 확인): PR 은 `origin`(Chanjun-kim/mirae-n-handson) 기준, 자동 리뷰는 GitHub Actions, GitHub MCP 연결 실패는 그대로 두고 `gh` CLI 를 쓴다.

1. **3회차에 받은 PR 이 origin 의 #1 이 맞는가?** `gh pr list` 에 열린 PR 이 #1 하나(제목 "재배포 정책 변경: 마감 과제도 사유가 있으면 재배포 허용, 교무 알림 발송 추가", 브랜치 `practice/bad-pr`)만 보인다. 이 PR 이 3회차의 것인지는 확인하지 못했다.
2. **Anthropic API 키가 있는가?** 저장소 시크릿(`ANTHROPIC_API_KEY`)에 직접 등록해야 한다. 등록 여부 미확인.
3. 현재 `main` 대비 미커밋 변경(`.claude/settings.json`, `hooks/` 등)을 PR 에 포함해도 되는가? 3회차 판정 이후 `ItemSearchCondition.java` 수정이 커밋됐는지는 `df9af27` 로그로 보아 커밋된 것으로 보이나, 테스트 `ItemSearchConditionTest.java` 는 확인하지 않았다.
4. 단계 1 에서 레거시(php, 8081)와 modern DB 를 함께 띄워도 되는가? (`docker compose --profile php --profile modern`. Docker 는 실행 중임을 확인함.)

## 5. 단계
### 단계 1 — 동작 보존 테스트 통과 (약 5분)
- **할 일**: DB · 레거시(8081) · 새 API(8080)가 떠 있는 상태에서 `characterization` 을 새 API 대상으로 실행한다.
- **만들거나 고칠 파일**: 없음. 결과는 `docs/project/RESULT.md` 에 기록한다.
- **확인 방법**:
  ```bash
  cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test
  ```
  출력의 `failed` 가 없고 `36 passed | 1 skipped` 이면 통과.

### 단계 2 — 자동 리뷰 코멘트 1회 (약 20분, 처음부터 끝까지 한 번)
- **할 일**: `.github/workflows/claude-review.yml` 을 푸시하고 `ANTHROPIC_API_KEY` 시크릿을 등록한 뒤(키 등록은 사람이 직접), PR #1 을 다시 열어 워크플로가 돌게 한다.
- **만들거나 고칠 파일**: `.github/workflows/claude-review.yml` (작성 완료, 미커밋), `docs/project/RESULT.md`.
- **확인 방법**: 브라우저에서 PR #1 의 Conversation 탭을 열면 자동 리뷰 코멘트가 1개 이상 있다. 또는 `gh pr view 1 --comments`. 파일에 키가 없는지: `grep -rnE "sk-[A-Za-z0-9]" .github` 결과 0건.

## 6. 완성 기준 (Day 4-1에서 점검)
- [ ] `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` 가 실패 0건으로 끝난다 (최신 소스로 기동한 서버 기준, 기동 시각이 마지막 소스 수정 시각보다 늦다).
- [ ] `cd modern/api && ./gradlew test` 가 실패 0건으로 끝난다.
- [ ] `git diff 949c0b7 -- characterization/tests characterization/__snapshots__` 출력이 비어 있다 (베이스라인 커밋 이후 테스트 · 스냅샷 미수정. `main` 에는 베이스라인이 없어 `main` 과 비교하지 않는다).
- [ ] 3회차 PR 주소가 `docs/project/RESULT.md` 에 적혀 있고, 그 PR 의 Conversation 에 자동 리뷰 코멘트가 1개 이상 달려 있다.
- [ ] 저장소의 `.github/` 또는 `ci-ports/` 아래 파일에 API 키 리터럴이 없다 (`grep -rnE "sk-[A-Za-z0-9]" .github ci-ports` 결과 0건).
