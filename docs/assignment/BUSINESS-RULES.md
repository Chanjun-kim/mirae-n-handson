# legacy/assignment-thymeleaf 비즈니스 규칙 후보 — 새 배포 화면

> 분석 범위: "새 배포" 화면(`GET /distributions/new`, `POST /distributions`)과 그 화면이 부르는 코드. 재배포 · 배포 목록 · 과제 목록 규칙은 뽑지 않았다.
> 근거는 `파일:줄번호` 형식이다. Java 파일은 `legacy/assignment-thymeleaf/src/main/java/com/example/assign/`, 템플릿은 `.../src/main/resources/templates/`, 스키마 · 시드는 `db/mariadb/init/` 기준이다.
> 진입점 · 의존 관계는 [ARCHITECTURE.md](ARCHITECTURE.md), 테이블 · 데이터 흐름은 [ERD.md](ERD.md) 를 본다.
> 이 문서는 코드를 읽어 뽑은 **후보**다. 실제 레거시를 실행해 확인하지 않았다.

**확신도 기준**
- 확실: 조건문 · SQL 로 확인했다.
- 추정: 이름 · 주석으로 짐작했거나, 이 모듈 밖(프레임워크 · 동시 실행)의 동작에 기대어 판단했다.

**검사 순서:** 배포 등록 요청은 다음 순서로 검사하고, 처음 걸린 검사의 메시지만 보여 준다.
과제 존재(`DistributionController.java:53-57`) → 마감(`:58-65`) → 과제 존재 재확인(`DistributionService.java:48-51`) → 학급 존재(`:52-55`) → 삭제 상태(`:56-58`) → 중복(`:59-61`).

## 1. 폼 표시

### BR-01 배포할 과제 후보는 진행 · 연장 · 마감 상태만 나온다
- **규칙:** 과제 선택 목록에는 `status` 가 `'O'` · `'X'` · `'C'` 인 과제만 나온다. 그 밖의 상태(`'D'` 등)는 목록에 없다.
- **근거:** `AssignmentDao.java:34-38`, `DistributionController.java:42`
- **근거 코드:**
  ```java
  + " WHERE a.status IN ('O', 'X', 'C') "                    // AssignmentDao.java:36
  ```
- **확신도:** 확실
- **비고:**
  - `'X'` · `'D'` 는 스키마 주석에 없다. 주석은 `O=진행 C=마감` 뿐이다(`01-schema.sql:87`). CHECK 제약도 없다.
  - 이 조건은 화면 목록에만 걸린다. 서버 검사는 `'D'` 만 막는다(BR-09).

### BR-02 과제는 마감 빠른 순, 학급은 ID 순으로 나온다
- **규칙:** 과제 목록은 `due_at` 오름차순, 같으면 `id` 오름차순이다. 학급 목록은 조건 없이 전체를 `id` 오름차순으로 보여 준다.
- **근거:** `AssignmentDao.java:37`, `ClassDao.java:21`
- **근거 코드:**
  ```java
  + " ORDER BY a.due_at ASC, a.id ASC";                     // AssignmentDao.java:37
  ```
  ```java
  jdbc.query("SELECT id, name, teacher_id FROM `class` ORDER BY id ASC", MAPPER);   // ClassDao.java:21
  ```
- **확신도:** 확실
- **비고:**
  - 학급을 교사로 거르지 않는다. 이 모듈에는 로그인 · 권한 코드가 없다(`build.gradle:24-30` 에 security 의존성 없음).
  - 학급 라벨에 `teacher_id` 가 그대로 나온다(`distribution_form.html:25`).

### BR-03 마감이 지난 과제는 폼에서 고를 수 없다. 연장 상태만 예외다
- **규칙:** 과제의 `due_at` 이 현재 시각보다 이전이고 `status` 가 `'X'` 가 아니면 선택지를 비활성화하고 라벨에 `[마감]` 을 붙인다. `'X'` 면 선택할 수 있고 `[연장]` 을 붙인다.
- **근거:** `distribution_form.html:16-18`
- **근거 코드:**
  ```html
  th:disabled="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'}"   <!-- distribution_form.html:16 -->
  ```
- **확신도:** 확실
- **비고:**
  - 화면 제약일 뿐이다. 서버 검사는 BR-04 가 따로 한다.
  - 마감 표시는 연도 없이 `MM-dd HH:mm` 이다(`distribution_form.html:17`).
  - `'C'`(마감) 상태라도 `due_at` 이 미래면 선택할 수 있다. 판단 기준은 `status` 가 아니라 `due_at` 이다(BR-04 도 같다).

## 2. 배포 등록 검증

### BR-04 마감이 지난 과제는 배포할 수 없다. 연장 상태만 예외다
- **규칙:** `due_at` 이 현재 시각보다 이전이고 `status` 가 `'X'` 가 아니면 배포하지 않고, `마감(yyyy-MM-dd HH:mm)이 지난 과제는 배포할 수 없습니다. [제목]` 오류와 함께 폼으로 돌려보낸다.
- **근거:** `DistributionController.java:58-65`
- **근거 코드:**
  ```java
  if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {       // DistributionController.java:60
      if (!"X".equals(a.getStatus())) {                           // :61
  ```
- **확신도:** 확실
- **비고:**
  - `isBefore` 라서 `due_at` 과 현재 시각이 **같으면 배포할 수 있다**.
  - 이 검사는 컨트롤러에만 있다. `DistributionService.distribute()` 를 다른 곳에서 부르면 마감 검사 없이 배포된다(`DistributionService.java:47-68`).
  - `'X'` 가 "연장"이라는 뜻은 주석(`DistributionController.java:59`)과 화면 라벨(`distribution_form.html:18`)에만 있다. 동작(예외 처리)은 코드로 확인했다.
  - `due_at` 은 NOT NULL 이라(`01-schema.sql:86`) `!= null` 분기가 거짓이 되는 경우는 스키마상 없다.

### BR-05 현재 시각은 2026-09-15 00:00:00 으로 고정되어 있다
- **규칙:** 마감 판단(BR-03 · BR-04)과 배포 시각(BR-12)에 쓰는 현재 시각은, JVM 시스템 속성 `app.clock` 이 `system` 이 아니면 `2026-09-15 00:00:00` 이다.
- **근거:** `AppClock.java:8-9`, `:14-19`, `Dockerfile:11`
- **근거 코드:**
  ```java
  private static final String FIXED = "2026-09-15 00:00:00";      // AppClock.java:9
  ```
  ```java
  if (sys != null && sys.equals("system")) {                      // AppClock.java:16
      return LocalDateTime.now().withNano(0);
  }
  return LocalDateTime.parse(FIXED, FMT);                          // :19
  ```
- **확신도:** 확실
- **비고:**
  - 주석은 "QA 기간 중 임시로 고정 … 아직 안 되돌림"이다(`AppClock.java:8`). 의도는 주석으로만 알 수 있다.
  - `Dockerfile:11` 의 실행 명령에 `-Dapp.clock=system` 이 없어, 컨테이너로 띄우면 고정 시각을 쓴다.
  - 이관할 때 이 고정값을 규칙으로 옮길지 버그로 볼지는 사람이 정해야 한다.

### BR-06 존재하지 않는 과제는 배포할 수 없다
- **규칙:** `assignmentId` 로 과제를 찾지 못하면 `존재하지 않는 과제입니다.` 오류와 함께 폼으로 돌려보낸다.
- **근거:** `DistributionController.java:53-57`, `DistributionService.java:48-51`, `AssignmentDao.java:41-47`
- **근거 코드:**
  ```java
  if (a == null) {                                                 // DistributionController.java:54
      ra.addFlashAttribute("error", "존재하지 않는 과제입니다.");
  ```
- **확신도:** 확실
- **비고:**
  - 같은 검사가 서비스에도 있다(`DistributionService.java:49-51`). 서비스 쪽 메시지는 `(id=…)` 를 붙이지만, 컨트롤러가 먼저 걸러서 보통은 나오지 않는다.
  - `findById` 는 상태 조건이 없다(`AssignmentDao.java:42`). `'D'` 과제도 "존재함"으로 본다.

### BR-07 존재하지 않는 학급에는 배포할 수 없다
- **규칙:** `classId` 로 학급을 찾지 못하면 `존재하지 않는 학급입니다. (id=…)` 오류와 함께 폼으로 돌려보낸다.
- **근거:** `DistributionService.java:52-55`, `DistributionController.java:69-71`
- **근거 코드:**
  ```java
  ClassRow c = classDao.findById(classId);                         // DistributionService.java:52
  if (c == null) {
      throw new IllegalArgumentException("존재하지 않는 학급입니다. (id=" + classId + ")");
  ```
- **확신도:** 확실
- **비고:** 이 검사는 서비스에만 있다. 조회한 `c` 는 존재 확인에만 쓰고 다른 곳에 쓰지 않는다(`DistributionService.java:52-67`).

### BR-08 삭제된 과제는 배포할 수 없다
- **규칙:** 과제 `status` 가 `'D'` 면 `삭제된 과제는 배포할 수 없습니다.` 오류와 함께 폼으로 돌려보낸다.
- **근거:** `DistributionService.java:56-58`
- **근거 코드:**
  ```java
  if ("D".equals(a.getStatus())) {                                 // DistributionService.java:56
      throw new IllegalStateException("삭제된 과제는 배포할 수 없습니다.");
  ```
- **확신도:** 확실
- **비고:**
  - 마감 검사(BR-04)가 먼저라서, 마감이 지난 `'D'` 과제는 이 메시지가 아니라 마감 메시지가 나온다.
  - `'D'` = 삭제라는 뜻은 메시지 문구로만 알 수 있다. 스키마 주석에는 없다(`01-schema.sql:87`).

### BR-09 서버는 `'D'` 외의 상태를 막지 않는다
- **규칙:** 요청으로 직접 보내면, 폼 목록(BR-01)에 없는 상태의 과제도 `'D'` 가 아니고 마감 전이면 배포된다.
- **근거:** `DistributionController.java:53-65`, `DistributionService.java:48-61`
- **근거 코드:**
  ```java
  if ("D".equals(a.getStatus())) {                                 // DistributionService.java:56 — 상태 검사는 이것 하나
  ```
- **확신도:** 확실
- **비고:**
  - 화면 조건(`IN ('O','X','C')`, `AssignmentDao.java:36`)과 서버 조건(`<> 'D'` 에 해당)이 다르다.
  - 과제 목록 화면은 `'R'` 을 "검수중"으로 표시한다(`assignments.html:26`, 범위 밖). `'R'` 과제가 이 경로로 배포될 수 있다.
  - 시드에는 `O` · `C` 외 상태가 없다(`02-seed.sql:119-125`).

### BR-10 같은 과제를 같은 학급에 두 번 배포할 수 없다
- **규칙:** `(assignment_id, class_id)` 가 같은 배포가 한 건이라도 있으면 `이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요.` 오류와 함께 폼으로 돌려보낸다.
- **근거:** `DistributionService.java:59-61`, `DistributionDao.java:46-52`, `01-schema.sql:92-103`
- **근거 코드:**
  ```java
  if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {   // DistributionService.java:59
  ```
  ```java
  "SELECT COUNT(*) FROM distribution WHERE assignment_id = ? AND class_id = ?"   // DistributionDao.java:49
  ```
- **확신도:** 확실
- **비고:**
  - DB 에 UNIQUE 제약은 없다(`01-schema.sql:92-103`). DAO 주석도 "UNIQUE 제약은 없음, 여기서 막는다"라고 적었다(`DistributionDao.java:46`).
  - `redistributed = 1` 인 행도 센다. 조건에 `redistributed` 가 없다.
  - 시드에는 이미 같은 조합이 두 건 있다(`02-seed.sql:133-134`, 과제 2 · 학급 1).
  - 두 요청이 동시에 들어오면 둘 다 0건을 보고 통과할 수 있다. 다만 BR-11 의 ID 충돌로 한쪽이 실패할 가능성이 크다(추정, 실행하지 않음).

## 3. 저장

### BR-11 배포 ID 는 기존 최댓값 + 1 이다
- **규칙:** 새 배포 ID 는 `distribution` 의 `MAX(id)` 에 1 을 더한 값이다. 행이 없으면 1 이다.
- **근거:** `DistributionDao.java:54-57`, `DistributionService.java:62`
- **근거 코드:**
  ```java
  Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM distribution", Long.class);   // DistributionDao.java:55
  return (max == null ? 0L : max.longValue()) + 1L;                                              // :56
  ```
- **확신도:** 확실
- **비고:**
  - `id` 에 AUTO_INCREMENT 가 없다(`01-schema.sql:93`).
  - 동시 등록 시 같은 ID 를 얻으면 PK 충돌이 `DataAccessException` 이 되어 `DB 오류로 배포에 실패했습니다.` 로 보일 것이다(`DistributionController.java:72-74`). 추정, 실행하지 않음.

### BR-12 배포 시각은 현재 시각, 재배포 표시는 0 으로 저장한다
- **규칙:** 배포 행은 `distributed_at = AppClock.now()`, `redistributed = 0` 으로 저장한다.
- **근거:** `DistributionService.java:63`, `DistributionDao.java:59-63`
- **근거 코드:**
  ```java
  "INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)",   // DistributionDao.java:61
  ```
- **확신도:** 확실
- **비고:** BR-05 때문에 `distributed_at` 은 기본 설정에서 항상 `2026-09-15 00:00:00` 이다.

### BR-13 저장된 행이 1건이 아니면 실패로 본다
- **규칙:** INSERT 반영 행 수가 1 이 아니면 `배포 저장에 실패했습니다.` 오류와 함께 폼으로 돌려보낸다.
- **근거:** `DistributionService.java:63-66`
- **근거 코드:**
  ```java
  if (n != 1) {                                                    // DistributionService.java:64
      throw new IllegalStateException("배포 저장에 실패했습니다.");
  ```
- **확신도:** 확실
- **비고:** 이 예외는 `@Transactional` 메서드 안에서 던지므로(`DistributionService.java:46`) Spring 기본 설정상 롤백 대상이다(RuntimeException). 롤백 동작 자체는 실행해 확인하지 않았다.

### BR-14 성공하면 배포 목록으로 가고 새 배포 번호를 알려 준다
- **규칙:** 배포가 저장되면 `배포가 등록되었습니다. (배포 #ID)` 메시지와 함께 `/distributions` 로 이동한다. 실패하면 오류 메시지와 함께 `/distributions/new` 로 돌아간다.
- **근거:** `DistributionController.java:67-76`
- **근거 코드:**
  ```java
  ra.addFlashAttribute("message", "배포가 등록되었습니다. (배포 #" + id + ")");   // DistributionController.java:68
  ```
- **확신도:** 확실
- **비고:**
  - DB 오류는 원인을 숨기고 `DB 오류로 배포에 실패했습니다.` 로만 보인다(`DistributionController.java:72-74`).
  - 폼으로 돌아갈 때 이전 선택값은 넘기지 않는다. flash 에 `error` 만 담는다(`:55`, `:62`, `:70`, `:73`).

## 4. 입력

### BR-15 과제 · 학급은 둘 다 필수다
- **규칙:** 화면에서는 두 select 가 `required` 이고 빈 선택지 값이 `""` 다. 서버는 `assignmentId` · `classId` 를 필수 `long` 으로 받는다.
- **근거:** `distribution_form.html:11-12`, `:23-24`, `DistributionController.java:50-51`
- **근거 코드:**
  ```java
  public String create(@RequestParam("assignmentId") long assignmentId,   // DistributionController.java:50
                       @RequestParam("classId") long classId,              // :51
  ```
- **확신도:** 추정
- **비고:**
  - 값이 없거나 숫자가 아닐 때 어떤 화면이 나오는지는 Spring 기본 처리에 달려 있고, 이 모듈에는 그 처리 코드가 없다. `DbErrorAdvice` 는 `DataAccessException` 만 받는다(`DbErrorAdvice.java:11`). 결과 화면은 확인하지 못했다.
