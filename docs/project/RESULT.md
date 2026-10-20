# 실전 프로젝트 결과 — 백엔드 / Chanjun-kim

## 단계 1 — 동작 보존 테스트 (2026-10-20)

| 대상 | 통과 | 실패 | 건너뜀 | 비고 |
|---|---|---|---|---|
| `modern/api` `./gradlew test` | 60 | 0 | 0 | 12:1x 실행 |
| characterization, 새 API(8080) | 36 | 0 | 1 | 12:24:13 재실행(최초 12:18:17), 결과 동일. 건너뜀은 `tests/example-units.test.js` 의 레거시 전용 케이스 |

- 8080 서버는 12:11:06 에 기동된 기존 프로세스를 그대로 썼다(재기동 시도는 권한 거부). 이관 코드 마지막 수정(10-06)보다 늦게 떴다.
- `characterization/` 테스트 · 스냅샷은 수정하지 않았다.

## 단계 2 — 자동 리뷰 워크플로
- `.github/workflows/claude-review.yml` 작성함. 아직 커밋 · 푸시하지 않았다(커밋은 사용자가 한다).
- `ANTHROPIC_API_KEY` 시크릿 등록은 사용자가 나중에 한다.
- 키 리터럴 점검: `grep -rnE "sk-[A-Za-z0-9]" .github ci-ports` 결과 0건(통과).
- 워크플로는 아직 한 번도 실행되지 않았다. `claude --bare -p` 와 설치 스크립트 URL 은 실행해 확인하지 못했다.

## 단계 3 — PR 코멘트
- 대상 PR: origin #1 (`practice/bad-pr`, 3회차 PR 인지 사용자 확인 "일단 그렇게").
- `gh` 기본 저장소가 `upstream` 이라 `gh pr view 1 --comments` 는 실패한다. `-R Chanjun-kim/mirae-n-handson` 을 붙여야 조회된다.
- PR #1 에 코멘트가 이미 있으나 작성자 `claude`, 형식 "판정: 반려" 로 3회차 `verify` 방식 리뷰다. 워크플로가 남긴 코멘트가 아니므로 완성 기준 ② 충족 근거로 쓰지 않는다.
- 남은 일: 워크플로 커밋 · 푸시(base 브랜치) → 시크릿 등록 → PR 재오픈 → 워크플로 작성 코멘트 확인.
