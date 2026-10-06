# 이관 검증 보고 1 — item-bank 문항 검색(`search.php` → `GET /api/items/search`)

판정: 반려

- 대상: `upstream/main...day1` (3커밋, 24개 파일, +4,779줄). 현재 체크 아웃된 `main` 은 `upstream/main` 과 같아 diff 가 비어 있어서, 이관 작업이 들어 있는 `day1` 을 대상으로 가정했다.
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준: `templates/approval-checklist.md` v0. 검증 중 코드는 수정하지 않았다. `day1` 은 임시 worktree 에서 실행했고 지금은 삭제했다.

## 기준별 결과

| 기준 | 결과 | 근거 |
|---|---|---|
| 1. 테스트 통과 | 미충족 (이관 동등성 미확인) | 아래 "실행한 테스트" 참고. 새 API 대상 characterization 을 실행하지 못했다 |
| 2. 치명 이슈 0건 | 충족 | 비밀값 · SQL 이어 붙이기 · 데이터 삭제 코드 없음 |
| 3. 요청 범위 이탈 없음 | 미충족 | 요청과 무관한 파일 다수 (이슈 W1) |
| 4. 컨벤션 준수 | 충족 (경고 · 제안 있음) | 컨벤션 위반은 찾지 못했다 |

## 실행한 테스트

| 명령 | 위치 | 결과 |
|---|---|---|
| `./gradlew test` | `modern/api` (day1) | 53건 통과 · 실패 0 · 건너뜀 0 |
| `npm test` (레거시 대상, `localhost:8081`) | `characterization` (day1) | 3개 파일 37건 통과 |
| `TARGET_BASE_URL=http://localhost:8080 npm test` (새 API 대상) | `characterization` | **실행하지 않음** |

- 새 API 대상 테스트를 실행하지 않은 이유: 8080 에서 떠 있는 서버가 `day1` 코드가 아니다.
- 근거: `GET /api/items/search` 가 400 `요청 값이 올바르지 않습니다` 를 돌려준다. 기존 `/api/items/{id}` 에 `search` 가 id 로 들어간 응답으로 보인다.
- 이 테스트는 "동작을 바꾸지 않았다" 를 보여 주는 유일한 증거다. `CLAUDE.md` 5절도 이 테스트가 전부 통과하기 전에는 완료로 보지 않는다고 적고 있다.
- 사람이 `day1` 을 체크 아웃해 `modern/api` 를 `bootRun` 으로 다시 띄워야 한다(먼저 `docker compose --profile modern up -d`).
- 서버를 띄운 뒤 위 새 API 대상 명령을 실행하고, 전부 통과하면 기준 1이 충족된다.
- 레거시 대상 37건 통과는 스냅샷이 레거시와 일치한다는 뜻일 뿐, 이관 코드의 검증이 아니다.

## 이슈 목록

### 경고

**W1. 요청 문장으로 설명되지 않는 파일이 다수 섞여 있다**
- 위치: `CLAUDE.md`(118줄 추가) · `.claude/skills/{convention-check,document-module,impact}/SKILL.md` · `docs/assignment/{ARCHITECTURE,BUSINESS-RULES,ERD}.md` · `docs/item-bank/{ARCHITECTURE,BUSINESS-RULES,CROSS-CHECK,ERD}.md`
- 근거: 이관 코드는 `modern/api` 의 `ItemSearch*` · `ItemSort`(8개 파일)와 테스트 3개, 그리고 `characterization` 의 테스트 · 스냅샷뿐이다. 나머지 13개 파일은 이관 1건으로 설명되지 않는다. 특히 `docs/assignment/*` 는 다른 모듈(assignment)의 문서다.
- 체크리스트 3번 "변경 파일 목록의 모든 파일이 요청 문장으로 설명된다" 에 해당한다. 1,792줄의 스냅샷도 이관 전 동작 기준선이라 이관 PR 에 함께 넣는지 별도 확인이 필요하다.
- 수정 방향: 문서 · Skill · `CLAUDE.md` 는 별도 PR 로 분리한다. 같이 가야 한다면 요청에 그 사실을 명시한다.

**W2. 새 API 대상 동등성 검증이 없다**
- 위치: `characterization/tests/item-bank.test.js` · `characterization/lib/target.mjs:21-24`
- 근거: 경로 매핑(`/search.php` → `/api/items/search`)은 들어 있어 실행 준비는 되어 있다. 다만 새 API 로 한 번도 실행된 결과가 없다. 기준 1 미충족의 원인이다.
- 수정 방향: 위 "실행한 테스트" 절차로 실행한다. 실패하면 이관 코드를 고치고 테스트 · 스냅샷은 고치지 않는다.

**W3. 태그 존재 확인이 호출마다 `tag` 전체를 읽는다**
- 위치: `modern/api/src/main/java/com/example/item/ItemSearchService.java:47-48`
- 근거: `tagRepository.findAll()` 로 전체 태그를 읽어 메모리에서 비교한다. 태그 파라미터가 있는 요청마다 반복된다. 레거시 `search.php:251-256` 가 옵션 목록을 순회하는 구조를 따른 것으로 보이며, 대소문자 구분 비교(`===`)를 지키려는 의도는 코드에 적혀 있다.
- 수정 방향: 동작은 바꾸지 말고 `existsByName` 류 조회로 바꿀 때 DB 컬레이션 때문에 대소문자 동작이 달라지지 않는지 먼저 확인한다. 그 확인이 어려우면 이관 후 개선 과제로 남긴다.

### 제안

**S1. 건수 조회와 행 조회가 서로 다른 문장이라 사이에 데이터가 바뀔 수 있다**
- 위치: `ItemSearchRepository.java:33`(count) · `:42-47`(page) · `ItemSearchService.java:34-35`
- 근거: 두 쿼리 사이에 변경이 끼면 `count` 와 `rows` 가 어긋날 수 있다. 서비스 클래스 주석(`ItemSearchService.java:12-13`)이 "레거시처럼 각각 실행하며 트랜잭션으로 묶지 않는다" 고 의도를 밝히고 있어 동작 보존이다.
- 수정 방향: 이관 후 개선 과제 목록에 올린다. 지금은 바꾸지 않는다.

**S2. 이관 중 확인한 "의심 동작" 이 코드에서 보존돼 있다 — 별도 목록으로 관리**
- 위치: `ItemSearchCondition.java:106`(주석) · `ItemSearchRepository.java` `where()` 의 `i.level < :maxLevel`
- 근거: 레거시 `search.php:214-217` 는 주석에 "전체 난이도 검색 (1~5 모두 포함)" 이라고 쓰고 실제로는 `level < 5` 를 건다. 이관 코드는 주석에 이 차이를 적고 코드대로 옮겼다. `CLAUDE.md` 5절 "레거시 버그는 이관 중 고치지 않고 의심 동작 목록으로 보고" 와 일치한다.
- 수정 방향: 레거시 수정 여부는 별도 의사결정이 필요하다. 이 문서의 "의심 동작" 으로 남긴다.

**S3. `ItemSearchRepository` 가 JPA 가 아니라 `NamedParameterJdbcTemplate` 을 쓴다**
- 위치: `ItemSearchRepository.java:12-16`(클래스 주석)
- 근거: 조건에 따라 WHERE · ORDER BY 가 바뀌어 JPQL `@Query` 로 표현하기 어렵다는 사유가 주석에 있다. `CLAUDE.md` 계층 규칙(Controller → Service → Repository)은 지키고, 모든 값은 바인딩 파라미터이며 ORDER BY 는 `ItemSort` 의 고정 문자열이다. 위반은 아니다.
- 수정 방향: 팀의 다른 리포지토리와 방식이 달라지므로 이 선택을 팀이 알고 있는지만 확인한다.

## 확인 필요 (줄번호 근거를 댈 수 없는 항목)

- 컬레이션에 따라 달라지는 동작(단원 코드 대소문자, 제목 정렬 순서)은 H2 슬라이스 테스트에서 재현되지 않는다(`ItemSearchRepositoryTest.java` 클래스 주석에 명시됨). 실제 MariaDB 에 붙은 새 API 대상 characterization(W2)이 이 부분을 확인하는 유일한 수단이다.
- 이관 코드를 레거시 `search.php` 와 줄 단위로 전부 대조하지는 않았다. 읽은 범위는 키워드 · 난이도 · 정렬 · 페이지 크기 · 태그 확인이며, 이 부분은 코드 주석의 레거시 줄 참조와 일치했다.

## 체크리스트 세부 대조

- [x] 테스트 파일 · 메서드: `@Disabled` · `skip` 없음, 기존 테스트 삭제 없음 (day1 의 변경은 모두 추가)
- [x] 비밀값 리터럴 없음, SQL 이어 붙임은 상수 조각과 바인딩 파라미터뿐
- [x] `System.out` · `printStackTrace` · `console.log` 없음, 로그는 SLF4J(`ItemSearchService.java` `log.debug`)이고 학생 식별자 · 이메일 · 토큰을 싣지 않음
- [x] 컨트롤러가 Repository · SQL 을 직접 호출하지 않음, 서비스는 같은 도메인의 `UnitRepository` · `TagRepository` 만 사용
- [x] 120자 초과 줄 없음(`ItemSearch*` · `ItemSort` · 테스트 3개 점검)
- [x] 의존성 · DB 스키마 · `application.yml` 변경 없음(`build.gradle` · `db/` · `legacy/` 는 diff 에 없음)
- [ ] 요청 문장으로 설명되지 않는 파일 있음 (W1)
- [ ] 새 API 대상 동등성 테스트 미실행 (W2)

## 반려 사유 요약

1. 새 API 대상 characterization 을 실행하지 못했다. 서버를 `day1` 코드로 다시 띄워야 한다(W2).
2. 요청과 무관한 파일 13개가 같은 변경분에 들어 있다(W1).

치명 이슈는 찾지 못했다. 두 건이 해소되면 재검증으로 승인 가능성이 높다.
