# 이관 검증 3회차 — 문항 검색(search.php → GET /api/items/search)

- 검증 범위: `upstream/main...HEAD -- modern characterization` + 작업 트리의 미커밋 변경
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관
- 수행: reviewer(체크리스트 대조), tester(테스트 실행·보강), 메인 세션(최종 판정)

## 판정: 승인 (작업 트리 기준, 커밋 전제)

초판은 반려(보류)였다. 새 API(8080)가 수정 전 코드로 기동돼 있어서 characterization 통과가 최신 코드를 보증하지 못했기 때문이다.
API 를 재기동(17:15:02, 소스 수정 17:04:09 이후)하고 재실행해 통과를 확인했으므로 기준 1 을 충족으로 바꾼다.

- 승인 대상은 작업 트리다. 수정(`ItemSearchCondition.java`)과 새 테스트(`ItemSearchConditionTest.java`)는 아직 커밋되지 않았다. 머지 전에 커밋해야 한다.
- 남은 `확인 필요` 2건(아래 이슈 목록)은 승인을 막지 않는 항목이다.

## 재검증 결과 (2차)

| 대상 | 통과 | 실패 | 건너뜀 | 비고 |
|---|---|---|---|---|
| characterization, 새 API(재기동 후 8080) | 36 | 0 | 1 | 실행 17:15:11. 건너뜀은 `tests/example-units.test.js:21` 의 레거시 전용 케이스 |
| `modern/api` 전체 | 60 | 0 | 0 | Gradle 이 `UP-TO-DATE` 로 재사용한 결과. 결과 XML 시각 17:08:02 가 소스 수정 17:04:09 이후라 최신 코드 기준 |

- 차이가 난 스냅샷은 없고, `characterization/` 는 수정하지 않았다.
- 서버 재기동은 메인 세션이 직접 했다(기존 프로세스 종료 후 `./gradlew bootRun`).

## 기준별 결과

| 기준 | 판정 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | modern/api 60/60, characterization 새 API 36 통과 · 실패 0(재기동 후 재확인) |
| 치명 이슈 0건 | 충족 | 비밀값 리터럴 없음, SQL 값 전부 바인딩, `ORDER BY` 는 `ItemSort` 고정 문자열 |
| 요청 범위 이탈 없음 | 충족 | 비교 범위의 모든 파일이 검색 엔드포인트 이관 또는 그 테스트. 의존성 · 스키마 · 설정 변경 없음 |
| 컨벤션 준수 | 충족 | 계층 · 예외 · 로깅 · Clock · import 위반 없음. 120자 제한은 확인 필요 |

## 테스트 결과 (tester)

| 대상 | 통과 | 실패 | 건너뜀 |
|---|---|---|---|
| `modern/api` `./gradlew test` | 60 | 0 | 0 |
| characterization, 새 API(8080) | 36 | 0 | 1 |
| characterization, 레거시(8081) | 37 | 0 | 0 |

- 건너뜀 1건은 `characterization/tests/example-units.test.js:21` 의 `it.skipIf(legacyOnly)` 로, 새 API 대상일 때 레거시 전용 케이스다(의도된 건너뜀).
- tester 가 추가한 테스트: `modern/api/src/test/java/com/example/item/ItemSearchConditionTest.java`(5개). `phpIntval` 의 `Long.MAX/MIN` 경계, 부호, 지수 표기를 덮는다.
- `characterization/` 의 테스트와 스냅샷은 수정되지 않았다. 변경 이력은 베이스라인 커밋 `949c0b7` 하나뿐이다.
- 실행하지 않음: assignment(8082) · grade(8083) 레거시(꺼져 있음, 이번 범위 아님).

## reviewer 와 tester 보고의 엇갈림

기준 판정이 엇갈리는 지점은 없다. 다만 두 보고가 각각 남긴 미확인 사항이 겹쳐서 판정을 보류한다.

## 이슈 목록

| 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|
| 경고 | `modern/api/src/main/java/com/example/item/ItemSearchCondition.java:216-222` | 수정(catch 제거 → `BigInteger` 크기 검사)이 미커밋이다. 서버 재기동 후 characterization 은 통과했다 | 수정 · 새 테스트 파일 커밋 |
| 경고(확인 필요) | `modern/api/src/test/java/com/example/item/ItemSearchConditionTest.java` `capsExponentOverflowAndZeroesInfinity` | `±9.3e18`(지수 표기로 long 범위 초과)을 최댓값 · 최솟값으로 붙이는 단언의 근거는 소스 주석뿐이다. PHP 의 `(int)"1e19"` 가 포화인지 아닌지 `legacy/item-bank-php/search.php` 로 실측한 근거가 없다 | 레거시 실측으로 확인. 다르면 코드는 고치지 않고 의심 동작으로 보고하고, 단언은 사람이 판단한다 |
| 제안 | `modern/api/src/main/java/com/example/item/ItemSearchRepository.java:41` | `ORDER BY` 를 문자열 연결한다. 값이 `ItemSort` 의 고정 문자열이라 현재는 안전하다 | 다른 경로의 값이 들어가지 못하게 주석이나 테스트로 고정 |
| 확인 필요 | 해당 없음 | 새로 쓰거나 고친 줄의 120자 이내 여부를 검사하지 못했다 | 줄 길이 점검(예: `convention-check` Skill) |

## 레거시 동작 보존 (근거)

- 난이도 비어 있음 → `level < 5`: `search.php:214-215` ↔ `ItemSearchRepository.java:81-83`
- 난이도 형식 오류 시 `(int)` 변환값으로 조회: `search.php:226-229` ↔ `ItemSearchCondition.java:117-119`
- 정렬: `search.php:279-325` ↔ `ItemSort.java:11-20`
- 결과 없음 문구: `search.php:658` ↔ `ItemSearchResponse.java` 의 `NO_RESULT_MESSAGE`
- 이 대응은 주석 인용과 grep 으로 확인했다. 응답이 레거시 스냅샷과 일치하는지는 characterization 이 보증한다(재기동 후 36 통과).

## 의심 동작 (이관 중 고치지 않음)

1. `level` 이 비면 주석("1~5 모두 포함")과 달리 난이도 5 가 제외된다 (`search.php:214-215`).
2. 키워드의 `%` · `_` 를 이스케이프하지 않는다 (`search.php:97`).
3. 단원은 DB 콜레이션, 태그는 `===` 로 대소문자 비교가 서로 다르다 (`search.php:125`, `:251-256`).
4. `unit` 정렬의 보조 키가 항상 난이도 내림차순이다 (`search.php:295`, `:297`).
5. 범위 밖 난이도는 경고만 내고 `(int)` 값으로 조회한다 (`search.php:223-229`).

## 승인에 필요한 조치

1. (완료) `modern/api` 재기동 후 characterization 새 API 대상 재실행.
2. 미커밋 변경(`ItemSearchCondition.java`, `ItemSearchConditionTest.java`, `.claude/skills/verify/SKILL.md`, 이 문서) 커밋 여부 결정. `.claude/agents/` 는 이번 범위 밖의 미추적 파일이다.
3. 위 경고(확인 필요) 처리: 지수 표기 경계의 레거시 실측, 120자 점검.
