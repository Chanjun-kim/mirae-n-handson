# 이관 검증 보고 2 — item-bank 문항 검색(`search.php` → `GET /api/items/search`) 재검증

판정: 반려

- 대상: `upstream/main...HEAD` (브랜치 `day1`, 4커밋, 25개 파일, +4,893줄). 마지막 커밋 `118d18d` 가 1차 리뷰 반영분이다.
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준: `templates/approval-checklist.md` v0. 검증 중 코드는 수정하지 않았다(이 문서만 추가).
- 1차 보고: `docs/verify/migration-review-1.md`. 이번에는 1차 지적 W1 · W2 · W3 의 해소 여부를 중심으로 다시 확인했다.

## 기준별 결과

| 기준 | 결과 | 근거 |
|---|---|---|
| 1. 테스트 통과 | 미충족 (이관 동등성 미확인) | `modern/api` 55건 통과, 레거시 대상 characterization 37건 통과. 새 API 대상 characterization 은 **실행하지 않음** (W2) |
| 2. 치명 이슈 0건 | 충족 | 비밀값 리터럴 없음, SQL 은 상수 조각 + 바인딩 파라미터, 삭제 · 덮어쓰기 코드 없음 |
| 3. 요청 범위 이탈 없음 | 미충족 | 요청 문장으로 설명되지 않는 파일 12개 (W1) |
| 4. 컨벤션 준수 | 충족 | 위반을 찾지 못했다. 제안 2건 |

## 실행한 테스트

| 명령 | 위치 | 결과 |
|---|---|---|
| `./gradlew cleanTest test` | `modern/api` | 55건 통과 · 실패 0 · 오류 0 · 건너뜀 0 (캐시가 아니라 다시 실행한 결과) |
| `npm test` (레거시 대상) | `characterization` | 3개 파일 37건 통과 · 실패 0 |
| `TARGET_BASE_URL=http://localhost:8080 npm test` (새 API 대상) | `characterization` | **실행하지 않음** |

- 1차 보고 때의 53건에서 55건으로 늘었다. `118d18d` 가 `ItemSearchServiceTest` 에 태그 테스트 2건(`acceptsRegisteredTag`, `warnsWhenTagDiffersOnlyInCase`)을 추가했다.
- 레거시 대상 37건 통과는 스냅샷이 레거시와 일치한다는 뜻일 뿐, 이관 코드의 검증이 아니다.

### 새 API 대상 테스트를 실행하지 않은 이유

- 8080 의 서버가 이 브랜치의 코드가 아니다. `GET /api/items/search?q=a` 와 `GET /api/items/search` 모두 400 `요청 값이 올바르지 않습니다` 를 돌려준다. 1차 보고 때와 같은 응답이다.
- 8080 을 점유한 프로세스(PID 33172)는 12:29:56 에 시작됐고, 마지막 커밋(`118d18d`)은 12:54:32 이다. 1차 리뷰 반영분이 반영된 서버가 아니다.
- 스킬 절차상 새 API 가 이 코드로 떠 있지 않으면 실행하지 않고 사람에게 기동을 요청한다.
- **요청**: `day1` 을 체크 아웃한 상태에서 8080 의 기존 프로세스를 내리고, 저장소 루트에서 `docker compose --profile modern up -d` 후 `cd modern/api && ./gradlew bootRun` 으로 다시 띄워 주세요. 기동되면 재검증하겠습니다.
- 재검증 명령: `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test`. 실패하면 이관 코드를 고치고, 테스트 · 스냅샷은 고치지 않는다(`CLAUDE.md` 4 · 5절).

## 1차 지적 해소 여부

| 1차 지적 | 상태 | 근거 |
|---|---|---|
| W1 요청과 무관한 파일 | **미해소** | 변경 파일 목록이 그대로다. 1차 보고서 자체(`docs/verify/migration-review-1.md`)가 12번째 파일로 늘었다 |
| W2 새 API 대상 동등성 미실행 | **미해소** | 위 절 참고. 서버가 여전히 구버전이다 |
| W3 태그 확인이 `findAll()` 로 전체 읽기 | **해소** | `ItemSearchService.java:50-52` 가 `findByName` 으로 바뀌었다. 아래 "확인한 사항" 참고 |

## 이슈 목록

### 경고

**W1. 요청 문장으로 설명되지 않는 파일이 같은 변경분에 섞여 있다**
- 위치: `CLAUDE.md`(118줄) · `.claude/skills/{convention-check,document-module,impact}/SKILL.md` · `docs/assignment/{ARCHITECTURE,BUSINESS-RULES,ERD}.md` · `docs/item-bank/{ARCHITECTURE,BUSINESS-RULES,CROSS-CHECK,ERD}.md` · `docs/verify/migration-review-1.md`
- 근거: 이관 코드는 `modern/api` 의 `ItemSearch*` · `ItemSort`(8개)와 테스트 3개, 그리고 `characterization` 의 테스트 · 스냅샷(2개)이다. 나머지 12개는 "엔드포인트 1개 이관" 한 문장으로 설명되지 않는다. 특히 `docs/assignment/*` 는 다른 모듈의 문서다. 체크리스트 3번 "변경 파일 목록의 모든 파일이 요청 문장으로 설명된다" 에 해당한다.
- 수정 방향: 문서 · Skill · `CLAUDE.md` 는 별도 PR 로 분리한다. 한 PR 로 가야 한다면 요청에 그 사실을 명시하고 승인을 받는다.

**W2. 새 API 대상 동등성 검증이 없다**
- 위치: `characterization/tests/item-bank.test.js` · `characterization/lib/target.mjs:21-24`
- 근거: "동작을 바꾸지 않았다" 를 보여 주는 유일한 증거인데 이 코드로 한 번도 실행된 결과가 없다. 컬레이션에 따라 달라지는 동작(단원 코드 대소문자, 제목 정렬 순서)은 H2 슬라이스 테스트에서 재현되지 않으므로(`ItemSearchRepositoryTest.java` 클래스 주석) 실제 MariaDB 에 붙은 실행이 필요하다. `CLAUDE.md` 5절은 이 테스트가 전부 통과하기 전에는 완료로 보고하지 않는다고 정한다.
- 수정 방향: 위 "요청" 절차대로 서버를 다시 띄운 뒤 실행한다.

### 제안

**S1. 건수 조회와 행 조회가 서로 다른 문장이라 사이에 데이터가 바뀔 수 있다**
- 위치: `ItemSearchRepository.java:33`(count) · `:42-47`(page) · `ItemSearchService.java:34-35`
- 근거: 두 쿼리 사이에 변경이 끼면 `count` 와 `rows` 가 어긋날 수 있다. 클래스 주석(`ItemSearchService.java:11-12`)이 레거시처럼 각각 실행한다고 밝혀 동작 보존이다.
- 수정 방향: 이관 후 개선 과제로 올린다. 지금은 바꾸지 않는다.

**S2. `ItemSearchRepository` 가 JPA 가 아니라 `NamedParameterJdbcTemplate` 을 쓴다**
- 위치: `ItemSearchRepository.java:12-16`
- 근거: 조건마다 WHERE · ORDER BY 가 바뀐다는 사유가 주석에 있다. 계층 규칙은 지키고, 값은 모두 바인딩 파라미터이며 ORDER BY 는 `ItemSort` 의 고정 문자열이다. 위반은 아니다.
- 수정 방향: 다른 리포지토리와 방식이 달라지므로 팀이 이 선택을 알고 있는지만 확인한다.

## 의심 동작 목록 (이관 중 고치지 않고 보존한 레거시 동작)

- 난이도를 비우면 `level < 5` 를 건다(난이도 5 제외). 레거시 `search.php:214-217` 의 주석은 "1~5 모두 포함" 이다. 이관 코드는 `ItemSearchCondition.java:106` 주석에 차이를 적고, `ItemSearchRepository.java:81-83` 에서 코드대로 옮겼다. 레거시 수정 여부는 별도 의사결정이 필요하다.

## 확인한 사항

- W3 해소 근거: 태그 이름에는 유니크 키가 있고(`db/mariadb/init/01-schema.sql:35-40`, `uk_tag_name`, 컬레이션 `utf8mb4_unicode_ci`) 대소문자만 다른 두 행은 존재할 수 없다. 그래서 `TagRepository.findByName`(`TagRepository.java:8`, `Optional`)이 여러 행을 돌려줘 예외를 낼 일이 없다. DB 가 대소문자를 무시하고 찾아도 `ItemSearchService.java:51` 이 `name::equals` 로 다시 비교해 레거시 `===`(`search.php:253`)와 같은 결과를 낸다. 새 테스트 `warnsWhenTagDiffersOnlyInCase` 가 이 경로를 확인한다.
- `ItemSearchService` 는 현재 시각을 쓰지 않는다(`now()` 호출 없음). 변경분에 `System.out` · `printStackTrace` · `@Disabled` · 와일드카드 import 없음. 빈 `catch` 없음(`ItemSearchCondition.java:216` 의 `catch` 는 `capToLong` 으로 처리한다).
- 120자 초과 줄 없음(글자 수 기준으로 `ItemSearch*` · `ItemSort` · 테스트 3개 점검).
- 테스트 3개 계층이 규칙대로다: 컨트롤러 `@WebMvcTest`, 서비스 `@ExtendWith(MockitoExtension.class)`, 리포지토리 `@DataJpaTest`. 모든 `@Test` 에 `@DisplayName` 이 붙어 있다(컨트롤러 5/5 · 서비스 17/17 · 리포지토리 9/9).
- 의존성(`build.gradle`) · DB 스키마(`db/`) · `application.yml` · `legacy/` 는 diff 에 없다.
- 로그에 학생 식별자 · 이메일 · 토큰이 없다(`ItemSearchService.java:36-37` 은 건수 · 페이지 · 경고 수).

## 확인 필요 (줄번호 근거를 댈 수 없는 항목)

- 이관 코드를 레거시 `search.php` 와 줄 단위로 전부 대조하지는 않았다. 읽은 범위는 키워드 · 난이도 · 정렬 · 페이지 크기 · 태그 확인이며, 코드 주석의 레거시 줄 참조와 일치했다. 나머지는 새 API 대상 characterization(W2)이 보증해야 한다.
- 작업 트리의 `.claude/skills/verify/` 는 아직 커밋되지 않았다. 이번 diff 에 포함되지 않았고, 이 검증 도구의 파일이라 이관과는 무관하다.

## 반려 사유 요약

1. 새 API 대상 characterization 을 실행하지 못했다. 8080 의 서버가 이 브랜치의 최신 코드가 아니다(W2). 사람이 서버를 다시 띄워야 한다.
2. 요청과 무관한 파일 12개가 같은 변경분에 들어 있다(W1).

치명 이슈는 없고 1차 지적 W3 는 해소됐다. 서버를 다시 띄운 뒤 새 API 대상 37건이 통과하고, W1 이 분리되거나 요청에 명시되면 승인할 수 있다.
