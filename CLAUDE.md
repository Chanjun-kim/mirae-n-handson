# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

답변과 문서(주석 · 커밋 메시지 · `docs/` 포함)는 한국어로 쓴다.

대상 코드: `modern/api`(Spring Boot 3.3 · Java 21 · Gradle) · `modern/web`(React 18 · TypeScript · Vite · Vitest). 도메인 용어: 문항 `item` · 단원 `unit` · 난이도 `level`(1~5) · 태그 `tag` · 학급 `class` · 배포 `distribution` · 제출 `submission` · 학생 `STU-<숫자>`.

## 1. 빌드 · 테스트 명령

```bash
# modern/api
cd modern/api && ./gradlew test                                           # 전체 테스트. DB 없이 통과한다(test 프로필 = H2 MariaDB 모드)
cd modern/api && ./gradlew test --tests com.example.item.ItemServiceTest  # 한 클래스
cd modern/api && ./gradlew build                                          # 테스트 포함 전체 빌드
cd modern/api && ./gradlew bootRun                                        # 8080. 먼저 저장소 루트에서 docker compose --profile modern up -d

# modern/web
cd modern/web && npm ci                                                   # 의존성 설치. npm install 을 쓰지 않는다
cd modern/web && npm run lint && npm run typecheck && npm test            # 커밋 전 세 가지 모두 실행
cd modern/web && npx vitest run src/components/ItemTable.test.tsx         # 한 파일
cd modern/web && npm run build                                            # dist/ 생성
cd modern/web && npm run dev                                              # 5173
```

- 코드를 바꾼 모듈의 테스트 명령을 실행하고, 통과 · 실패 수를 답변에 적는다. 실행하지 않았으면 "실행하지 않음"이라고 적는다.
- `modern/web` 은 lint · typecheck · test 중 하나라도 실패하면 답변에 "완료"라고 쓰지 않는다.
- 테스트 출력은 통과 · 실패 수와 실패한 테스트 이름만 답변에 싣는다.

## 2. 코딩 컨벤션

### modern/api — 계층
- 패키지는 도메인 단위(`com.example.item`, `com.example.assignment`)다. 공통 코드는 `common`, 설정은 `config`.
- 호출 방향은 Controller → Service → Repository → 엔티티다. 컨트롤러는 Repository 를 주입받지 않고 SQL 문자열을 갖지 않는다.
- 서비스가 다른 도메인의 데이터를 쓸 때는 그 도메인의 서비스를 주입받는다. 다른 도메인의 Repository 를 주입받지 않는다.
- 요청 · 응답은 record DTO(`*Response`, `*Request`)다. 컨트롤러 메서드의 반환 타입에 엔티티 클래스가 나오지 않는다.
- 요청 본문 검증은 DTO 필드의 Bean Validation 어노테이션 + 컨트롤러 파라미터의 `@Valid` 로 한다.
- `*Service` 클래스는 현재 시각을 `Clock` 빈(`common/ClockConfig`)을 주입받아 얻는다. `*Service` 안에서 `LocalDateTime.now()` · `Instant.now()` 를 인자 없이 호출하지 않는다.

### modern/api — 예외
- 예외 → HTTP 응답 변환은 `common/GlobalExceptionHandler` 에서만 한다. 컨트롤러 메서드 안에 try-catch 가 없다.
- 매핑: `NotFoundException` · 없는 경로 → 404, 검증 실패 → 400, `IllegalStateException` → 409, 그 밖의 예외 → 500. 응답 본문은 `ErrorResponse`.
- 없는 리소스는 서비스에서 `NotFoundException` 을 던진다.
- 비어 있는 `catch` 블록이 없다. `catch` 블록은 예외를 다시 던지거나 도메인 예외로 감싸 던진다.

### modern/api — 로깅
- 로그는 `private static final Logger log = LoggerFactory.getLogger(<클래스>.class)` 로만 남긴다. `System.out` · `System.err` · `printStackTrace()` 가 없다.
- 로그 메시지에 학생 식별자(`STU-…`) · 이메일 · 토큰 값을 넣지 않는다. 건수 · 내부 ID(`classId` 등)는 넣어도 된다.

### modern/api — 테스트
- 새 public 서비스 메서드와 새 엔드포인트마다 테스트가 1개 이상 있다.
- 컨트롤러는 `@WebMvcTest(<컨트롤러>.class)`, 서비스는 `@ExtendWith(MockitoExtension.class)`, 리포지토리 쿼리는 `@DataJpaTest` 로 테스트한다.
- 테스트 메서드 이름은 확인하는 동작을 camelCase 로 쓰고(예: `rejectsOtherOrigins`), `@DisplayName` 에 한국어 설명을 붙인다.
- 테스트는 `src/test/resources/application-test.yml`(H2)로 돈다. 테스트 설정에 MariaDB 주소를 넣지 않는다.

### modern/api — 이름 · 형식
- 클래스 `PascalCase`, 메서드 · 필드 `camelCase`, 상수 `UPPER_SNAKE_CASE`. 약어는 첫 글자만 대문자(`ItemDto`).
- 비교 · 계산에 쓰는 숫자 리터럴(0 · 1 제외)은 `static final` 상수로 둔다(예: `MAX_LEVEL = 5`).
- 들여쓰기 4칸. 와일드카드 import(`import x.*;`)가 없다. 새로 쓰거나 고친 줄은 120자 이내.

### modern/web — 구조
- 컴포넌트는 `src/components/<이름>.tsx` 파일 하나에 하나. 파일명 = 컴포넌트명(`PascalCase`).
- 데이터 훅은 `src/hooks/use<이름>.ts` 이고 `useApiQuery` 를 기반으로 한다. 요청 취소는 `AbortSignal` 로 한다.
- API 호출 함수는 `src/api/` 에 두고 `src/api/client.ts` 의 `getJson` 을 거친다. `fetch` 를 호출하는 파일은 `client.ts` 하나다.
- 응답 타입은 `src/api/types.ts` 에 두고 백엔드 JSON 필드명을 그대로 쓴다.
- 오류는 `ApiError` 로 받는다. 컴포넌트에서 `response.status` 를 비교하지 않는다.

### modern/web — 컴포넌트 · 타입
- 함수 컴포넌트만 쓴다. 클래스 컴포넌트 · `React.FC` · `defaultProps` 가 없다.
- props 타입은 같은 파일에 `interface <이름>Props` 로 선언한다.
- 이벤트 콜백 prop 이름은 `on<동작>`(예: `onSelect`), 컴포넌트 안 구현 함수는 `handle<동작>`.
- 타입 import 는 `import type` 으로 쓴다(ESLint `consistent-type-imports`).
- 쓰지 않는 변수 · 인자는 `_` 로 시작한다.

### modern/web — 테스트
- 새로 만드는 컴포넌트 · 훅에는 같은 폴더에 `<이름>.test.tsx` / `<이름>.test.ts` 가 있다.
- 조회는 Testing Library 의 `getByRole` · `getByText` 를 쓴다. `getByTestId` 를 쓰지 않는다.
- 네트워크는 `src/test/mockFetch.ts` 로 가로채고, 데이터는 `src/test/fixtures.ts` 를 쓴다. 테스트에서 `localhost:8080` 을 호출하지 않는다.
- `toMatchSnapshot()` 을 쓰지 않는다.

## 3. 금지 사항

`legacy/` 는 분석 · 이관 대상이며 허락 없이 수정하지 않는다.

먼저 사람에게 묻고 승인받은 뒤에만 하는 변경:
- DB 스키마 · 시드(`db/`), 마이그레이션 파일 추가.
- 의존성 추가 · 업그레이드(`build.gradle` 의 `dependencies`, `package.json`). 질문에 이유와 대안을 함께 적는다.
- `modern/api/src/main/resources/application.yml` 의 DB 접속 정보 · Hikari 풀 설정.

하지 않는 것:
- `package-lock.json` 을 손으로 고치거나 지우기.
- 운영 비밀값(계정 · 토큰 · 키)을 코드 · 설정에 리터럴로 넣기. 예외: `application.yml` 의 실습용 더미 `app-pass`.
- 운영 DB 호스트(`prod-db` 등)를 가리키는 설정 · 명령 만들기.
- `@Transactional` 메서드 안에서 외부 HTTP 호출, 또는 DB 조회가 끝난 뒤의 반복 계산(예: `ReportService.sign()`). `ReportService.buildClassReport` 가 현재 이 규칙을 어기고 있다(장애 실습용, `TODO` 주석).
- TypeScript 에서 `any` · `as unknown as` · `@ts-ignore`. 타입을 맞출 수 없으면 이유를 답변에 적고 묻는다.
- `eslint-disable` 주석을 사유 없이 쓰기. 쓰면 같은 줄에 사유를 적는다.
- `dangerouslySetInnerHTML`, 커밋에 남은 `console.log`.
- `.env` · `.env.*` 파일을 읽거나 만들기.
- 테스트를 지우거나 `@Disabled` · `it.skip` 으로 바꿔 통과시키기.
- 요청에 없는 파일 수정(포맷팅 · import 정리 포함). 고쳤다면 파일과 이유를 답변에 적는다.

## 4. 아키텍처 안내

- 엔드포인트: `GET /api/units`, `GET /api/units/{code}/items`, `GET /api/items/{id}`, `GET /api/distributions/{id}`, `POST /api/distributions/{id}/redistribute`, `GET /api/classes/{id}/report`. 새 엔드포인트는 `/api/<도메인 복수형>` 아래에 두고, 컨트롤러 · 서비스 · 리포지토리 · 테스트를 같은 도메인 패키지에 만든다.
- 공개 문항은 `status = 'A'` 로 판단한다(삭제 플래그 아님).
- 기본 프로필은 로컬 MariaDB(`itembank`, 3306)에 붙는다. Hikari 풀은 운영 값과 같게 max 5 · connection-timeout 3000ms 라서, 트랜잭션이 커넥션을 쥐는 시간이 길어지면 풀이 고갈된다.
- CORS 는 `config/WebConfig` 에서 `http://localhost:5173` 의 `/api/**` GET 만 허용한다.
- `modern/web` 의 API 주소는 `VITE_API_BASE ?? 'http://localhost:8080'`. `VITE_API_BASE=""` 면 상대 경로를 쓰고 Vite 프록시(`/api` → 8080)를 탄다.
- 데이터 흐름(web): 컴포넌트 → `src/hooks/use*` → `src/api/*` → `getJson` → `modern/api`.
- 레거시 규칙을 옮길 때는 근거를 `파일:줄번호` 로 답변에 적는다(예: `legacy/item-bank-php/search.php:214`).
- 이관 결과는 레거시 스냅샷과 비교한다: `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test`. 실패하면 이관 코드를 고치고 테스트 · 스냅샷은 고치지 않는다.

## 5. 완료 기준

- 이관 · 리팩토링 작업은 `characterization` 의 `npm test` 가 전부 통과하기 전에는 완료라고 보고하지 않는다.
- 테스트가 실패하면 실패한 케이스와 차이를 그대로 보고한다. 요약해서 "거의 됐다"고 말하지 않는다.
- 테스트를 통과시키려고 `characterization/` 의 테스트 코드나 스냅샷 파일을 고치지 않는다. 스냅샷을 바꿔야 한다고 판단되면 멈추고 묻는다.
- 레거시 동작이 버그로 보여도 이관 중에는 고치지 않는다. "의심 동작" 목록으로 따로 보고한다.
