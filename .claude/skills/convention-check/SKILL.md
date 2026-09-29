---
name: convention-check
description: modern/api · modern/web 변경 코드를 CLAUDE.md 코딩 컨벤션 · 금지 사항 기준으로 점검하고 위반을 파일:줄번호로 보고한다. "컨벤션 점검", "커밋 전 점검", "리뷰 전에 확인", "규칙 어긴 데 있나 봐 줘" 같은 요청이나 커밋 · PR 직전에 쓴다.
argument-hint: "[경로 ...] (생략하면 git 변경 파일 전체)"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 컨벤션 점검

## 원칙
- 코드를 직접 고치지 않는다. 판정과 수정 방향만 보고한다.
- 근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남긴다.

## 1. 점검 대상 정하기
- 인자로 경로를 받으면 그 경로(폴더면 하위 `.java` · `.ts` · `.tsx`)만 본다.
- 인자가 없으면 `git status --porcelain` 과 `git diff HEAD --name-only` 로 변경 파일을 모은다.
  `??`(아직 add 하지 않은 새 파일)도 포함한다. 삭제된 파일 · `legacy/` 는 제외한다.
- "추가된 줄"은 `git diff HEAD -U0 -- <파일>` 의 `+` 줄이다. 새 파일은 모든 줄이 추가된 줄이다.
- 대상이 없으면 "점검할 변경 파일 없음"만 출력하고 끝낸다.

## 2. 점검 항목
modern/api (`.java`)
1. `*Controller` 가 `*Repository` 를 필드 · 생성자로 받지 않고, SQL 문자열과 `try` 블록이 없다.
2. 컨트롤러 메서드 반환 타입에 `@Entity` 클래스가 없다. 엔티티 목록은 Grep `@Entity` 로 얻는다.
3. `*Service` 안에 인자 없는 `LocalDateTime.now()` · `Instant.now()` 가 없다(`Clock` 주입).
4. `System.out` · `System.err` · `printStackTrace()` 가 없고, 빈 `catch` 블록이 없다.
5. `import …*;` 가 없고, 추가된 줄이 120자 이내다.

modern/web (`.ts` · `.tsx`)
6. `any` 타입 · `as unknown as` · `@ts-ignore` 가 없고, `eslint-disable` 은 같은 줄에 사유가 있다.
7. `fetch(` 는 `src/api/client.ts` 에만 있고, `dangerouslySetInnerHTML` · `console.log` 가 없다.

공통 (테스트 파일)
8. `getByTestId` · `toMatchSnapshot(` · `localhost:8080` 이 없고,
   추가된 줄에 `@Disabled` · `it.skip` 이 없다.

- 파일 종류에 맞지 않는 항목은 "해당 없음"으로 둔다.
- Grep 으로 후보를 찾은 뒤 반드시 Read 로 그 줄과 주변을 확인하고 판정한다.
  주석 · 문자열 안의 단어나 규칙이 허용하는 예외(예: `client.ts` 의 `fetch`)는 위반이 아니다.

## 3. 출력 형식 (순서 고정)

### 판정 요약
- 점검 대상: N개 파일 (인자 경로 / git 변경 파일)
- 결과: 통과 · 위반 N건 · 확인 필요 N건
- 항목별: `1 통과 · 2 위반(1) · 3 해당 없음 · …` 처럼 8개를 한 줄로

### 위반 목록
| 파일:줄번호 | 어긴 규칙 | 수정 방향 |
|---|---|---|
| `modern/api/src/main/java/com/example/item/ItemController.java:27` | 1. 컨트롤러가 Repository 주입 | `ItemService` 메서드로 옮기고 컨트롤러는 서비스만 호출 |

- 위반이 없으면 표 대신 "위반 없음"이라고 쓴다.
- 줄번호를 특정할 수 없는 의심은 표에 넣지 않고, 표 아래 "확인 필요" 목록에 이유와 함께 적는다.
