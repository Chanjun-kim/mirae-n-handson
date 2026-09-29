# legacy/assignment-thymeleaf 아키텍처 — 새 배포 화면

> 분석 범위: **"새 배포" 화면 하나**(`GET /distributions/new`, `POST /distributions`)와 그 화면이 부르는 코드. 배포 목록 · 재배포(`DistributionService.redistribute`) · 과제 목록은 다루지 않는다.
> 근거는 `파일:줄번호` 형식이다. Java 파일은 `legacy/assignment-thymeleaf/src/main/java/com/example/assign/`, 템플릿은 `.../src/main/resources/templates/`, 설정 · 빌드 파일은 `legacy/assignment-thymeleaf/` 기준이다. compose 파일은 저장소 루트 기준이다.

## 1. 모듈 개요

- Spring Boot 3.3.4 · Java 17 · Thymeleaf · JdbcTemplate 로 만든 과제 배포 관리 화면이다(`build.gradle:3`, `:11`, `:25-28`). 버전 표기는 `1.4.2`(`build.gradle:8`, `layout.html:35`).
- DB 는 MariaDB `itembank` 를 문항 은행과 함께 쓴다(`application.properties:2`, `docker-compose.yml:61`). 테이블 정의는 `db/mariadb/init/01-schema.sql:72-115`.
- 계층은 Controller → Service → DAO(JdbcTemplate) 이지만, 새 배포 화면의 컨트롤러는 DAO 를 직접 부르기도 한다(§4).
- compose 에서 `8082:8080` 으로 노출되고 `thymeleaf` 프로필로만 뜬다(`docker-compose.yml:52-54`).
- 현재 시각은 `AppClock.now()` 가 준다. 시스템 속성 `app.clock=system` 이 없으면 `2026-09-15 00:00:00` 고정값을 돌려준다(`AppClock.java:9`, `:14-19`). `Dockerfile:11` 의 실행 명령에는 이 속성이 없다.

## 2. 폴더 구조 (범위 안 파일만 역할 표기)

```
legacy/assignment-thymeleaf/
├── Dockerfile                      11줄  gradle 빌드 → temurin 17 JRE 로 assignment.jar 실행
├── build.gradle                    38줄  web · thymeleaf · jdbc · mariadb 드라이버
└── src/main/
    ├── java/com/example/assign/
    │   ├── AssignApplication.java  12줄  부트 진입점
    │   ├── AppClock.java           28줄  현재 시각(고정값) · 날짜 형식
    │   ├── web/
    │   │   ├── DistributionController.java 101줄  form() · create() (범위 안), list() · redistribute() (범위 밖)
    │   │   ├── DbErrorAdvice.java   18줄  DataAccessException → db_error 화면
    │   │   └── AssignmentController.java 35줄  (범위 밖)
    │   ├── service/
    │   │   └── DistributionService.java 701줄  distribute() 47~68줄만 범위 안
    │   ├── dao/
    │   │   ├── AssignmentDao.java   66줄  findAllForSelect() · findById()
    │   │   ├── ClassDao.java        42줄  findAll() · findById()
    │   │   └── DistributionDao.java 108줄 countByAssignmentAndClass() · nextId() · insert()
    │   └── model/                   AssignmentRow(79) · ClassRow(32) 사용. 나머지 3개는 범위 밖
    └── resources/
        ├── application.properties  14줄  DB 접속 · Hikari(max 4, timeout 5000ms)
        └── templates/
            ├── distribution_form.html 34줄  새 배포 화면
            ├── layout.html          37줄  head · nav · flash · footer 조각
            └── db_error.html        11줄  DB 오류 화면(DbErrorAdvice)
```

## 3. 진입점

| URL · 메서드 | 처음 실행되는 코드 | 주요 호출 (근거) |
|---|---|---|
| `GET /distributions/new` · 새 배포 폼 | `DistributionController.form()` (`DistributionController.java:40-47`) | `assignmentDao.findAllForSelect()`(`:42`), `classDao.findAll()`(`:43`), `AppClock.now()`(`:44`) → 뷰 `distribution_form`(`:46`) |
| `POST /distributions` · 폼 제출 | `DistributionController.create()` (`DistributionController.java:49-77`) | `assignmentDao.findById()`(`:53`) → 마감 검사(`:58-65`) → `distributionService.distribute()`(`:67`) → `redirect:/distributions`(`:76`) 또는 `redirect:/distributions/new`(`:56`, `:63`, `:71`, `:74`) |

- 메뉴 "새 배포" 링크가 `GET /distributions/new` 로 간다(`layout.html:26`).
- 폼은 `POST /distributions` 로 `assignmentId` · `classId` 두 값을 보낸다(`distribution_form.html:8`, `:11`, `:23`).
- `GET` 처리 중 `DataAccessException` 이 나면 `DbErrorAdvice` 가 `db_error` 화면을 돌려준다(`DbErrorAdvice.java:11-16`). `POST` 는 컨트롤러가 직접 잡는다(`DistributionController.java:72-75`).

## 4. 의존 관계

### 4.1 호출 그래프 (범위 안)

```
distribution_form.html ──th:replace──▶ layout.html (head · nav · flash · footer)   distribution_form.html:3,5,7,32

DistributionController.form()
  ├─▶ AssignmentDao.findAllForSelect()      DistributionController.java:42
  ├─▶ ClassDao.findAll()                    :43
  └─▶ AppClock.now()                        :44

DistributionController.create()
  ├─▶ AssignmentDao.findById()              DistributionController.java:53
  ├─▶ AppClock.now() · AppClock.fmt()       :58, :62
  └─▶ DistributionService.distribute()      :67      (@Transactional, DistributionService.java:46)
        ├─▶ AssignmentDao.findById()        DistributionService.java:48
        ├─▶ ClassDao.findById()             :52
        ├─▶ DistributionDao.countByAssignmentAndClass()  :59
        ├─▶ DistributionDao.nextId()        :62
        ├─▶ AppClock.now()                  :63
        └─▶ DistributionDao.insert()        :63

AssignmentDao · ClassDao · DistributionDao ─▶ JdbcTemplate (생성자 주입)
  AssignmentDao.java:17-19, ClassDao.java:16-18, DistributionDao.java:19-21
```

### 4.2 관찰

- **컨트롤러가 DAO 를 직접 주입받는다.** `DistributionController` 는 `DistributionService` 외에 `AssignmentDao` · `ClassDao` 를 받는다(`DistributionController.java:22-30`). `form()` 은 서비스를 거치지 않는다.
- **과제 조회가 두 번 일어난다.** `create()` 가 `assignmentDao.findById()` 로 한 번(`DistributionController.java:53`), `distribute()` 가 같은 메서드로 한 번 더 조회한다(`DistributionService.java:48`). 앞의 조회는 트랜잭션 밖이다.
- **배포 검증이 두 층에 나뉘어 있다.** 마감 검사는 컨트롤러에만(`DistributionController.java:59-65`), 학급 존재 · 삭제 상태 · 중복 검사는 서비스에만(`DistributionService.java:52-61`) 있다. 과제 존재 검사는 양쪽에 있다(`DistributionController.java:54-57`, `DistributionService.java:49-51`). 규칙은 BUSINESS-RULES.md 에 적는다.
- **시각은 정적 유틸리티에서 얻는다.** `AppClock` 은 빈이 아닌 정적 메서드이고(`AppClock.java:14`), 컨트롤러 · 서비스가 각각 호출한다.
- **예외 처리 경로가 둘이다.** `create()` 는 `IllegalArgumentException` · `IllegalStateException` · `DataAccessException` 을 직접 잡아 flash 메시지로 바꾼다(`DistributionController.java:69-75`). 그 밖의 경로의 `DataAccessException` 은 `DbErrorAdvice` 가 받고 `System.out.println` 으로 남긴다(`DbErrorAdvice.java:13`).
- 외부 라이브러리는 Spring Boot 스타터와 MariaDB 드라이버뿐이다(`build.gradle:24-30`).
