# legacy/assignment-thymeleaf ERD · 데이터 흐름 — 새 배포 화면

> 분석 범위: "새 배포" 화면(`GET /distributions/new`, `POST /distributions`)이 읽고 쓰는 테이블과 데이터 흐름. `submission` 은 이 화면에서 쓰지 않아 관계만 적는다.
> 근거는 `파일:줄번호` 형식이다. Java 파일은 `legacy/assignment-thymeleaf/src/main/java/com/example/assign/` 기준, 스키마 · 시드는 `db/mariadb/init/` 기준이다.
> 진입점 · 의존 관계는 [ARCHITECTURE.md](ARCHITECTURE.md), 규칙은 [BUSINESS-RULES.md](BUSINESS-RULES.md) 를 본다.

## 1. 테이블 관계

```mermaid
erDiagram
    unit ||--o{ assignment : "unit_id"
    assignment ||--o{ distribution : "assignment_id"
    class ||--o{ distribution : "class_id"
    distribution ||--o{ submission : "distribution_id"

    unit {
        INT id PK
        VARCHAR code UK
        VARCHAR name
        TINYINT grade
    }
    assignment {
        INT id PK
        VARCHAR title
        INT unit_id FK
        DATETIME due_at
        CHAR status
    }
    class {
        INT id PK
        VARCHAR name
        VARCHAR teacher_id
    }
    distribution {
        INT id PK
        INT assignment_id FK
        INT class_id FK
        DATETIME distributed_at
        TINYINT redistributed
    }
    submission {
        INT id PK
        INT distribution_id FK
        VARCHAR student_id
        DATETIME submitted_at
        DECIMAL score
    }
```

## 2. 테이블 정의 (범위 안)

| 테이블 | 컬럼 · 제약 | 근거 |
|---|---|---|
| `unit` | `id` PK, `code` UNIQUE, `name`, `grade` | `01-schema.sql:11-18` |
| `class` | `id` PK, `name` VARCHAR(50), `teacher_id` VARCHAR(20). 모두 NOT NULL | `01-schema.sql:75-80` |
| `assignment` | `id` PK, `title`, `unit_id` FK→`unit`, `due_at` DATETIME **NOT NULL**, `status` CHAR(1) 기본 `'O'` | `01-schema.sql:82-90` |
| `distribution` | `id` PK(자동 증가 아님), `assignment_id` FK, `class_id` FK, `distributed_at` NOT NULL, `redistributed` TINYINT 기본 0. `(assignment_id, class_id)` UNIQUE **없음**(인덱스만 따로) | `01-schema.sql:92-103` |
| `submission` | `distribution_id` FK→`distribution` | `01-schema.sql:105-115` |

- `assignment.status` 의 스키마 주석은 `O=진행 C=마감` 두 값뿐이다(`01-schema.sql:87`). 코드는 `X`(연장, `DistributionController.java:59-61`) · `D`(삭제, `DistributionService.java:56`) 도 쓴다. 스키마에 CHECK 제약은 없다.
- 모든 `id` 에 AUTO_INCREMENT 가 없다(`01-schema.sql:76`, `:83`, `:93`). 배포 ID 는 코드가 만든다(§3).

## 3. SQL 목록 (범위 안)

| # | 위치 | 종류 | 테이블 | SQL 요지 |
|---|---|---|---|---|
| S1 | `AssignmentDao.java:21-24`, `:34-38` | SELECT | `assignment` LEFT JOIN `unit`, 서브쿼리 `distribution` | `WHERE a.status IN ('O','X','C') ORDER BY a.due_at ASC, a.id ASC` |
| S2 | `ClassDao.java:21` | SELECT | `class` | `ORDER BY id ASC`, 조건 없음 |
| S3 | `AssignmentDao.java:41-47` | SELECT | S1 과 같은 기본 SELECT | `WHERE a.id = ?`. 상태 조건 없음. 없으면 `null` |
| S4 | `ClassDao.java:24-30` | SELECT | `class` | `WHERE id = ?`. 없으면 `null` |
| S5 | `DistributionDao.java:47-52` | SELECT | `distribution` | `COUNT(*) WHERE assignment_id = ? AND class_id = ?` |
| S6 | `DistributionDao.java:54-57` | SELECT | `distribution` | `COALESCE(MAX(id), 0)` → 코드에서 `+1` |
| S7 | `DistributionDao.java:59-63` | INSERT | `distribution` | `(id, assignment_id, class_id, distributed_at, redistributed=0)` |

- S1 · S3 의 기본 SELECT 는 과제별 배포 학급 수(`class_cnt`)를 서브쿼리로 계산하지만(`AssignmentDao.java:23`), 새 배포 화면은 이 값을 쓰지 않는다(`distribution_form.html:14-18`).
- `unit` 은 LEFT JOIN 이다(`AssignmentDao.java:24`). 스키마상 `unit_id` 는 NOT NULL FK 라 짝이 없는 행은 생기지 않는다(`01-schema.sql:85`, `:89`).

## 4. 화면별 데이터 흐름

### 4.1 `GET /distributions/new` — 폼 표시

```
요청 ─▶ form() ─▶ S1 (과제 후보) ─▶ model.assignments          DistributionController.java:42
               ─▶ S2 (학급 전체) ─▶ model.classes              :43
               ─▶ AppClock.now() ─▶ model.now                   :44
      ─▶ distribution_form.html
           과제 <option>: 마감 지남 && status≠'X' 이면 disabled   distribution_form.html:16
                          라벨 "id. 제목 (단원코드, 마감 MM-dd HH:mm)" + [연장]/[마감]  :17-18
           학급 <option>: "이름 (teacher_id)"                     :25
           두 select 모두 HTML required                           :11, :23
```

### 4.2 `POST /distributions` — 배포 등록

```
assignmentId, classId (@RequestParam long)                     DistributionController.java:50-51
 ├─ S3 과제 조회 → 없으면 flash error, /distributions/new 로    :53-57
 ├─ 마감 검사: due_at < now && status≠'X' → flash error         :58-65
 └─ distribute() [트랜잭션]                                      DistributionService.java:46-47
      ├─ S3 과제 재조회 → 없으면 IllegalArgumentException        :48-51
      ├─ S4 학급 조회 → 없으면 IllegalArgumentException          :52-55
      ├─ status = 'D' → IllegalStateException                    :56-58
      ├─ S5 중복 건수 > 0 → IllegalStateException                :59-61
      ├─ S6 새 ID = MAX(id)+1                                     :62
      ├─ S7 INSERT (distributed_at = AppClock.now())              :63
      └─ 반영 행 수 ≠ 1 → IllegalStateException                   :64-66
 ├─ 성공: flash "배포가 등록되었습니다. (배포 #id)" → /distributions   DistributionController.java:68, :76
 ├─ IllegalArgument/IllegalState: flash e.getMessage() → /distributions/new  :69-71
 └─ DataAccessException: flash "DB 오류로 배포에 실패했습니다." → /distributions/new  :72-74
```

- flash 메시지는 `layout.html` 의 `flash` 조각이 `message` · `error` 로 보여 준다(`layout.html:28-30`).

## 5. 시드 데이터로 본 화면 상태

`AppClock` 고정 시각 `2026-09-15 00:00:00`(`AppClock.java:9`) 기준이다.

| 과제 | due_at · status (`02-seed.sql`) | 폼 표시 |
|---|---|---|
| 1 · 2 | 2026-08-20 · 2026-08-28, `C` (`:120-121`) | disabled, `[마감]` |
| 3 | 2026-09-10, `O` (`:122`) | disabled, `[마감]` |
| 4 · 5 · 6 | 2026-09-25 · 10-02 · 09-30, `O` (`:123-125`) | 선택 가능 |

- 시드에 `X` · `D` 상태 과제는 없다(`02-seed.sql:119-125`).
- 시드의 배포 4번은 배포 3번과 같은 (과제 2, 학급 1) 조합이다(`02-seed.sql:133-134`). 스키마가 중복을 막지 않는다는 것과 맞다.
