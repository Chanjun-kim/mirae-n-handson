# RCA — a-connection-pool

## 요약

- **무슨 일**: 2026-09-16 14:37 ~ 14:52 KST(약 15분) 동안 API 의 DB 커넥션 풀(max 5)이 고갈됐다. 14:40:52 ~ 14:51:47(약 11분)에 5xx 186건(500 49 · 502 82 · 504 55)이 나갔다.
- **원인**: 학급 리포트(`ReportService.buildClassReport`)가 DB 조회 뒤의 서명 계산까지 트랜잭션에 넣어 요청 1건이 커넥션을 15.5 ~ 21.5초 쥐었고, 리포트 요청이 분당 10 ~ 19건으로 늘자 커넥션 5개가 모두 잡혔다.
- **확대**: nginx 업스트림 2대 중 1대는 일시 제외되고 다른 1대는 연결을 거부해, 리포트와 무관한 경로까지 502 가 났다.
- **확신 수준: 중간**. 구조는 코드와 로그로 확인했다. 같은 코드인데 이 구간에서만 요청이 15초 이상 걸린 이유와 첫 사건인 9.81초 쿼리의 원인은 확인하지 못했다.
- **조치**: 서명 계산을 트랜잭션 밖으로 옮긴다(6절). 누수 WARN 은 첫 500 보다 3분 먼저 나왔으므로 경보로 건다(8절).

## 1. 수집 범위

대상: `incident-logs/a-connection-pool/` 의 로그 4개. 이 절은 "어느 시간대를 볼 것인가"만 정한다. 원인 판단은 포함하지 않는다.

조사 방법: 파일을 통째로 읽지 않고 `wc` · `head` · `tail` · `grep -c` · `grep -n` · `awk` 집계로만 확인했다.

### 1.1 파일별 기록 기간 · 줄 수 · 시각 형식

| 파일 | 줄 수 | 기록 기간(KST) | 시각 형식 | 타임존 |
|---|---:|---|---|---|
| `app.log` | 5,371 | 09-16 06:43:56 ~ 15:29:51 | `2026-09-16T06:43:56.753+09:00` (ISO-8601, 밀리초) | KST. 오프셋 `+09:00` 이 모든 줄에 명시됨(1,927건 전부) |
| `nginx-access.log` | 21,086 | 09-16 06:43:56 ~ 15:29:59 | `[16/Sep/2026:06:43:56 +0900]` (초 단위) | KST. 오프셋 `+0900` 이 모든 줄에 명시됨(21,086건 전부) |
| `nginx-error.log` | 168 | 09-16 09:12:03 ~ 14:51:47 | `2026/09/16 09:12:03` (초 단위) | 표기 없음. KST 로 추정(근거는 아래) |
| `mariadb-slow.log` | 21 | 09-16 09:14:07 ~ 14:37:52 (원문은 UTC 00:14:07 ~ 05:37:52) | `# Time: 260916  0:14:09` (YYMMDD H:MM:SS) + `SET timestamp=<epoch 초>` | **UTC**. 표기 없음(근거는 아래) |

보충:

- `app.log` 의 5,371줄 중 시각으로 시작하는 로그 항목은 1,927건이고, 나머지 3,444줄은 스택 트레이스 연속 줄이다. 이 문서의 건수는 모두 시각이 있는 항목 줄 기준이다(키워드 `grep -c` 는 스택 줄까지 세어 더 크게 나온다).
- `app.log` 레벨 분포: INFO 1,626 · WARN 203 · ERROR 98.
- `nginx-access.log` 상태 코드 분포: 200 19,605 · 400 25 · 404 1,250 · 409 20 · 500 49 · 502 82 · 504 55.
- `nginx-access.log` 는 User-Agent 뒤에 필드가 없다. 응답 시간(`request_time` 등)이 기록되지 않으므로 타임아웃은 504 와 `nginx-error.log` 로만 확인할 수 있다.
- `nginx-error.log` 레벨 분포: error 137 · warn 28 · info 3.
- `mariadb-slow.log` 는 슬로 쿼리 3건(각 7줄)이다.
- `nginx-access.log` URL 에 학생 식별자, `nginx-error.log` 에 Authorization 헤더 일부가 들어 있다. 발췌를 문서로 옮길 때는 가린다.

### 1.2 KST 변환 규칙

| 파일 | 규칙 |
|---|---|
| `app.log` | 변환 없음 |
| `nginx-access.log` | 변환 없음 |
| `nginx-error.log` | 변환 없음(KST 로 취급) |
| `mariadb-slow.log` | `# Time` 값에 **+9시간**. 또는 `SET timestamp` 값을 `TZ=Asia/Seoul date -r <epoch>` 로 변환 |

근거:

- `nginx-error.log`: `upstream timed out` 55건의 시각이 `nginx-access.log`(+0900)의 504 응답 55건과 초 단위로 55건 모두 일치한다.
- `mariadb-slow.log`: `SET timestamp` 를 UTC 로 풀면 `# Time` 과 같은 시간대에 놓이고, 두 값의 차이가 `Query_time` 과 맞는다. 즉 `SET timestamp` = 쿼리 시작, `# Time` = 쿼리 종료이며 둘 다 UTC 다.

| 항목 | `SET timestamp` (UTC) | `Query_time` | `# Time` (원문) | 시작(KST) | 종료(KST) |
|---|---|---:|---|---|---|
| 1 | 00:14:07 | 2.32초 | 0:14:09 | 09:14:07 | 09:14:09 |
| 2 | 02:02:40 | 2.04초 | 2:02:42 | 11:02:40 | 11:02:42 |
| 3 | 05:37:42 | 9.81초 | 5:37:52 | 14:37:42 | 14:37:52 |

### 1.3 평소 수준

14:37 이전과 14:52 이후(기록 기간 전체에서 이상 구간을 뺀 나머지)의 값이다.

| 파일 | 지표 | 평소 수준 |
|---|---|---|
| `app.log` | ERROR | 0건 |
| `app.log` | WARN | 분당 0~1건. 전부 `state conflict`(409) 한 종류이며 하루 20건(구간 밖 17건, 구간 안 3건) |
| `app.log` | 타임아웃(`Connection is not available, request timed out`) | 0건 |
| `nginx-access.log` | 5xx | 0건 |
| `nginx-access.log` | 4xx | 분당 평균 약 2.7건, 최대 8건. 이상 구간에서도 같은 수준(0~6건) |
| `nginx-access.log` | 전체 요청 수 | 분당 평균 40건, 최대 68건(15:14). 이상 구간은 34~58건으로 평소 범위 안 |
| `nginx-error.log` | error | 0건 |
| `nginx-error.log` | warn · info | 5줄(09:12 · 13:05 warn `buffered to a temporary file`, 10:42 · 11:47 · 13:18 info) |
| `mariadb-slow.log` | 슬로 쿼리 | 2건(09:14 · 11:02 KST) |

### 1.4 분 단위 건수 (KST 14:30 ~ 14:55)

- `app.log`: 누수 WARN = `ProxyLeakTask : Connection leak detection triggered`, 풀 ERROR = `Connection is not available, request timed out after 3000ms`, 409 WARN = `state conflict`.
- 풀 ERROR 와 같은 분에 같은 건수로 `SQL Error` WARN 과 `unhandled exception` ERROR 가 1건씩 더 찍힌다(각 49건). 표에는 풀 ERROR 만 싣는다.
- 빈 칸은 0건.

| 시각 | app 누수 WARN | app 풀 ERROR | app 409 WARN | access 500 | access 502 | access 504 | nginx-error error | nginx-error warn | slow 쿼리 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 14:30 ~ 14:32 | | | | | | | | | |
| 14:33 | | | 1 | | | | | | |
| 14:34 ~ 14:36 | | | | | | | | | |
| **14:37** | 1 | | | | | | | | 1 |
| 14:38 | 2 | | | | | | | | |
| 14:39 | 3 | | | | | | | | |
| **14:40** | 14 | 1 | | 1 | | | | | |
| 14:41 | 13 | 6 | | 6 | | 3 | 3 | | |
| 14:42 | 10 | 2 | 1 | 2 | 13 | 5 | 18 | 2 | |
| 14:43 | 14 | 10 | | 10 | 2 | 5 | 7 | 1 | |
| 14:44 | 11 | 2 | | 2 | 5 | 6 | 11 | 4 | |
| 14:45 | 9 | 8 | | 8 | 14 | 5 | 19 | 3 | |
| 14:46 | 12 | 4 | | 4 | 16 | 6 | 22 | 3 | |
| 14:47 | 9 | 5 | | 5 | 6 | 3 | 9 | 1 | |
| 14:48 | 10 | 4 | | 4 | 6 | 5 | 11 | 4 | |
| 14:49 | 12 | 7 | | 7 | 8 | 8 | 16 | 4 | |
| 14:50 | 11 | | 1 | | 11 | 7 | 18 | 4 | |
| **14:51** | 3 | | 1 | | 1 | 2 | 3 | | |
| 14:52 | | | 1 | | | | | | |
| 14:53 ~ 14:55 | | | | | | | | | |
| 합계 | 134 | 49 | 5 | 49 | 82 | 55 | 137 | 26 | 1 |

유형별 최초 · 최종 시각(KST):

| 파일 | 유형 | 건수 | 최초 | 최종 |
|---|---|---:|---|---|
| `mariadb-slow.log` | 슬로 쿼리 3번(9.81초) | 1 | 14:37:42 시작 | 14:37:52 종료 |
| `app.log` | 누수 WARN | 134 | 14:37:52 | 14:51:37 |
| `app.log` | 풀 ERROR(3000ms 타임아웃) | 49 | 14:40:52 | 14:49 |
| `app.log` | `unhandled exception` ERROR | 49 | 14:40:52 | 14:49:51 |
| `nginx-access.log` | 5xx 전체 | 186 | 14:40:52 (500) | 14:51:47 (504) |
| `nginx-error.log` | `upstream timed out` | 55 | 14:41:06 | 14:51:47 |
| `nginx-error.log` | `no live upstreams` | 68 | 14:42:04 | 14:51:02 |
| `nginx-error.log` | `connect() failed (Connection refused)` | 14 | 14:42 ~ 14:51 구간 안 | 〃 |
| `nginx-error.log` | `upstream server temporarily disabled` (warn) | 26 | 14:42 ~ 14:50 구간 안 | 〃 |

파일 간 건수 대조(집계가 서로 맞는지 확인한 것이며 인과 판단이 아니다):

- `app.log` 풀 ERROR 49 = `unhandled exception` 49 = `nginx-access.log` 500 49. 분 단위 분포도 같다.
- `nginx-access.log` 504 55 = `nginx-error.log` `upstream timed out` 55(초 단위 일치).
- `nginx-access.log` 502 82 = `no live upstreams` 68 + `connect() failed` 14.
- `nginx-error.log` error 137 = 502 82 + 504 55.

### 1.5 이상 구간 제안

| 구분 | 시각(KST) | 기준 |
|---|---|---|
| 시작 | **14:37** | 평소 0건인 `app.log` 누수 WARN 이 처음 나온 분(14:37:52). 같은 분에 슬로 쿼리 1건(14:37:42 ~ 14:37:52) |
| 사용자 응답 오류 시작 | 14:40 | 첫 500(14:40:52). `app.log` 첫 ERROR 도 같은 초 |
| 끝 | **14:52** | 마지막 누수 WARN 14:51:37(누수 커넥션 반환 INFO 는 14:51:49 까지), 마지막 5xx · nginx error 14:51:47. 14:52 부터 모든 지표가 평소 수준 |

이상 구간: **14:37 ~ 14:52 KST (약 15분)**. 이 중 5xx 가 나간 시간은 14:40:52 ~ 14:51:47(약 11분).

### 1.6 수집 구간 제안(앞뒤 여유)

| 구분 | 여유 | 시각(KST) |
|---|---|---|
| 앞 | 30분 | 14:07 부터 |
| 뒤 | 10분 | 15:02 까지 |

**수집 구간: 14:07 ~ 15:02 KST (55분).**

- 앞쪽을 더 길게 두는 이유: 사용자 응답 오류(14:40)보다 3분 먼저 경고가 시작됐으므로, 그보다 앞선 신호가 더 있는지 확인할 구간이 필요하다. 또 이상 구간과 비교할 직전의 정상 구간이 있어야 한다.
- 뒤쪽은 정상 복귀 확인용이다. 14:52 이후 5xx · ERROR 가 0건임을 이미 확인했으므로 10분이면 된다.

파일별 적용:

| 파일 | 볼 범위 | 줄 범위 |
|---|---|---|
| `app.log` | 14:07 ~ 15:02 | 1135 ~ 5287 (4,153줄. 대부분 스택 트레이스 연속 줄) |
| `nginx-access.log` | 14:07 ~ 15:02 | 17405 ~ 19805 (2,401줄) |
| `nginx-error.log` | 파일 전체 | 1 ~ 168 (구간 안은 6 ~ 168. 그 앞 5줄은 09:12 ~ 13:18 의 warn · info) |
| `mariadb-slow.log` | 파일 전체 | 1 ~ 21 (구간 안은 3번 항목 15 ~ 21줄. 1 · 2번은 비교용) |

구간을 넓혀도 줄 범위 안에서 `grep -n` 으로 필요한 유형만 뽑아 본다. `app.log` 는 항목 줄(`^2026-`)만 먼저 추리고, 스택 트레이스는 유형별로 1건씩만 확인한다.

## 2. 타임라인

대상: 1.6 의 수집 구간(14:07 ~ 15:02 KST). 시각은 모두 KST 이며 `mariadb-slow.log` 만 +9시간 변환했다. 로그에 있는 사건만 적었고 원인 해석은 포함하지 않는다.

읽는 법:

- 형식: 시각 | 출처(파일:줄번호) | 사건 요약 | 원문 발췌(80자 이내, `…` 는 생략).
- 출처 약칭: `app` = `app.log`, `access` = `nginx-access.log`, `ngx-err` = `nginx-error.log`, `slow` = `mariadb-slow.log`.
- 반복되는 메시지는 첫 발생 줄에 "이후 N건/분"으로 묶었다. 분 단위 건수 전체는 1.4 표에 있다.
- **(선후 불확실)**: 서로 다른 파일의 시각이 몇 초 이내라 순서를 단정할 수 없는 곳. `access` · `ngx-err` · `slow` 는 초 단위, `app` 은 밀리초 단위다. `access` 는 응답 완료 시각만 기록하므로 요청이 들어온 시각은 알 수 없다.
- 마스킹: 학생 식별자는 등장 순서대로 `학생A` 식 별칭으로 바꿨다. IP 는 역할 별칭(`API-1` = 요청을 처리한 앱 서버, `API-2` = 연결을 거부한 업스트림, `교사단말A` ~ `D` = 리포트를 요청한 클라이언트, 등장 순)으로 바꾸고, 구분이 필요 없는 것은 `10.20.x.x` 로 적었다.

### 2.1 이상 구간 이전 (14:07 ~ 14:36)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:07 ~ 14:36 | access:17405 ~ | 전체 요청 분당 32 ~ 56건(평균 44.9건). 5xx 0건 | — |
| 14:10:05 | app:1148 · access:17539 | 학급 리포트 조회 200. 이후 14:18:13 까지 5건(분당 0 ~ 1건) | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:11:38 | app:1154 · access:17605 | 재배포 요청 409. 평소에도 나오는 유형이며 이후 14:14:03 · 14:33:45 에 1건씩 | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=…` |
| 14:18:13 | app:1175 · access:17914 | 이상 구간 이전의 마지막 학급 리포트 조회. 이후 14:38:01 까지 19분 48초 동안 리포트 요청 0건 | `built class report for class 1: 3 distributions, 10 submissions` |
| 14:19:38 | app:1182 · access:17977 | 배포 7 재배포 200 | `redistributed distribution 7 (assignment 5, class 3) reason=학생A 결석…` |

### 2.2 가장 많은 오류보다 앞선 구간 (14:37 ~ 14:40:51)

가장 많이 나오는 오류는 5xx(186건, 첫 건 14:40:52)와 `app` 누수 WARN(134건, 첫 건 14:37:52)이다. 그보다 앞서거나 그 사이에 있는, 평소와 다른 줄을 모두 적는다.

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:37:42 | slow:20 | 슬로 쿼리 시작(`SET timestamp`). 이날 3건 중 가장 긴 쿼리 | `SET timestamp=1789537062;` |
| 14:37:52 | slow:15 · 18 · 21 | 슬로 쿼리 종료. 9.81초, 4행 반환, 1,284,310행 검사. 접속 호스트 API-1. **(선후 불확실: 아래 누수 WARN 과 같은 초)** | `…from submission s1_0 where s1_0.distribution_id=5 order by s1_0.student_id;` |
| 14:37:52.199 | app:1243 | 커넥션 누수 WARN 첫 건(스레드 exec-6, 커넥션 `@6e8f14d3`). 이후 2건/분(14:38), 3건/분(14:39), 9 ~ 14건/분(14:40 ~ 14:50), 3건/분(14:51). 합계 134건이며 스택에 모두 `ReportService…buildClassReport` 줄이 있다 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3…` |
| 14:38:01 | access:18801 | 학급 3 리포트 200. 19분 48초 만의 리포트 요청이며 클라이언트 교사단말A(Macintosh UA)의 구간 내 첫 요청. 이후 같은 클라이언트가 같은 경로를 14:51:24 까지 40건 요청(처음 10건은 22 ~ 31초 간격). **(선후 불확실: 아래 app 2줄과 같은 초)** | `"GET /api/classes/3/report HTTP/1.1" 200 266` |
| 14:38:01.900 | app:1258 | 학급 리포트 생성 완료 로그(exec-6). 같은 로그가 3건/분(14:38 · 14:39), 10건/분(14:40), 8 ~ 15건/분(14:41 ~ 14:50), 5건/분(14:51). `access` 기준 리포트 요청(오류 응답 포함)은 14:41 ~ 14:50 에 10 ~ 19건/분 | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:38:01.902 | app:1259 | 누수로 보고됐던 커넥션 반환 INFO 첫 건(exec-6, `@6e8f14d3`). 이후 누수 WARN 과 같은 건수(134건)로 14:51:49 까지 반복 | `Previously reported leaked connection …Connection@6e8f14d3 on thread …exec-6…` |
| 14:38:20 | app:1261 | 누수 WARN 2번째(같은 커넥션 `@6e8f14d3`) | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3…` |
| 14:38:29 | access:18817 · app:1278 | 배포 5 재배포 200(클라이언트 교사단말A). 사유 값이 `null`. 14:37 슬로 쿼리의 조건도 `distribution_id=5` | `redistributed distribution 5 (assignment 3, class 3) reason=null` |
| 14:38:51 | app:1280 | 누수 WARN 이 2번째 커넥션(`@2ac19f07`)에서 처음 발생 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@2ac19f07…` |
| 14:39:50 | app:1331 | 누수 WARN 이 3번째 커넥션(`@71d4b8e2`)에서 처음 발생 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@71d4b8e2…` |
| 14:39:54 | access:18881 | 두 번째 클라이언트 교사단말B 의 리포트 요청 시작(학급 1). 이후 학급 1 · 2 를 반복 요청 | `"GET /api/classes/1/report HTTP/1.1" 200 271` |
| 14:40:04 | app:1351 | 누수 WARN 이 4번째 커넥션(`@3f6c2a91`)에서 처음 발생 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@3f6c2a91…` |
| 14:40:12 | app:1370 | 누수 WARN 이 5번째 커넥션(`@5b0e9e0c`)에서 처음 발생. 누수 WARN 에 나오는 커넥션은 이 5개가 전부(각 24 ~ 29건) | `Connection leak detection triggered for org.mariadb.jdbc.Connection@5b0e9e0c…` |
| 14:40:43 | access:18912 | 세 번째 클라이언트 교사단말C(Macintosh UA)의 리포트 요청 시작(학급 1). 14:49:53 까지 29건 | `"GET /api/classes/1/report HTTP/1.1" …` |

### 2.3 응답 오류 구간 (14:40:52 ~ 14:51:49)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:40:52.319 | app:1521 · 1522 | 커넥션 풀 타임아웃 첫 건(WARN `SQL Error` 1줄 + ERROR 1줄, exec-3). 이후 2 ~ 10건/분으로 14:49:51 까지 49건. `waiting` 값은 1 ~ 7 | `itembank-pool - Connection is not available, request timed out after 3000ms…` |
| 14:40:52.319 | app:1522 | 위 ERROR 의 풀 상태 값 | `(total=5, active=5, idle=0, waiting=4)` |
| 14:40:52.320 | app:1523 | 처리되지 않은 예외 ERROR 첫 건. 풀 타임아웃과 같은 건수(49건). 경로별: 단원 문항 19 · 단원 목록 14 · 문항 7 · 리포트 5 · 배포 4 | `unhandled exception on /api/units/M6-2/items` |
| 14:40:52 | access:18918 | 첫 500. 이후 2 ~ 10건/분으로 14:49:51 까지 49건. **(선후 불확실: 위 app 3줄과 같은 초)** | `"GET /api/units/M6-2/items HTTP/1.1" 500 162` |
| 14:41:06 | ngx-err:6 · access:18933 | 첫 업스트림 타임아웃과 첫 504(같은 초, 학급 3 리포트, 클라이언트 교사단말A). 이후 2 ~ 8건/분으로 14:51:47 까지 55건. 504 는 55건 모두 리포트 경로 | `upstream timed out (110: Connection timed out) while reading response header…` |
| 14:41 ~ 14:50 | access (분 단위 집계) | 리포트를 뺀 나머지 요청의 기록 건수가 분당 20 ~ 39건. 직전(14:34 ~ 14:39)은 34 ~ 50건, 직후(14:51 ~ 14:53)는 37 ~ 47건. 전체 요청 수는 34 ~ 58건으로 평소 범위 | — |
| 14:42:01 | ngx-err:10 | 업스트림 일시 제외 warn 첫 건. 이후 1 ~ 4건/분으로 14:50:52 까지 26건 | `upstream server temporarily disabled while reading response header from upstream` |
| 14:42:01 | ngx-err:11 · access:18970 | 업스트림 연결 거부 error 첫 건과 첫 502(학급 2 리포트). 연결 거부는 14:50:52 까지 14건. **(선후 불확실: 위 warn 과 같은 초. 파일 안 순서는 warn 이 먼저)** | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:42:04 | ngx-err:12 | "살아 있는 업스트림 없음" error 첫 건. 14:51:02 까지 68건. 502 는 합계 82건(2 ~ 16건/분)이며 리포트 32건, 그 밖의 경로 50건 | `no live upstreams while connecting to upstream, client: 10.20.x.x…` |
| 14:42:42 | app:2174 · access:18998 | 재배포 요청 409(평소 유형). 이후 14:50:22 에 1건 | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=…` |
| 14:44:19 | app:2929 · access:19078 | 배포 8 재배포 200 | `redistributed distribution 8 (assignment 6, class 2) reason=보강 수업 후 재배포` |
| 14:49:51 | app:4980 · 4981 · access:19305 | 마지막 풀 타임아웃 ERROR · 마지막 처리되지 않은 예외 · 마지막 500 | `"GET /api/units/M5-3/items HTTP/1.1" 500 162` |
| 14:49:53 | access:19307 | 클라이언트 교사단말C 의 마지막 리포트 요청 | `"GET /api/classes/1/report HTTP/1.1" …` |
| 14:50:52 | ngx-err:158 · 159 | 마지막 업스트림 일시 제외 warn · 마지막 연결 거부 error | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:51:02 | ngx-err:166 · access:19347 | 마지막 "살아 있는 업스트림 없음" · 마지막 502 | `no live upstreams while connecting to upstream, client: 10.20.x.x…` |
| 14:51:24 | access:19364 | 클라이언트 교사단말A(Macintosh UA)의 마지막 리포트 요청 | `"GET /api/classes/3/report HTTP/1.1" …` |
| 14:51:37 | app:5251 | 마지막 누수 WARN | `Connection leak detection triggered for org.mariadb.jdbc.Connection@…` |
| 14:51:47 | ngx-err:168 · access:19381 | 마지막 업스트림 타임아웃 · 마지막 504 · 마지막 5xx(학급 3 리포트, 클라이언트 교사단말D) | `upstream timed out (110: Connection timed out) while reading response header…` |
| 14:51:49.146 | app:5266 · 5267 | 연속되던 리포트 생성 완료 로그의 마지막 건과 마지막 커넥션 반환 INFO(2밀리초 차이) | `Previously reported leaked connection …Connection@6e8f14d3 on thread …exec-10…` |

### 2.4 이상 구간 이후 (14:51:50 ~ 15:02)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:51:52 | app:5268 · access:19386 | 재배포 요청 409(평소 유형). 14:52:09 에 1건 더(app:5269) | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=…` |
| 14:52 ~ 15:02 | access:19382 ~ 19805 | 5xx 0건. `app` ERROR 0건. `ngx-err` 는 14:51:47 이후 기록 없음 | — |
| 14:59:15 | app:5283 | 학급 리포트 조회 200. 이후 14:59:52 · 15:01:20 에 1건씩(분당 0 ~ 2건). 누수 WARN 없음 | `built class report for class 3: 3 distributions, 10 submissions` |

### 2.5 찾아봤지만 로그에 없는 것

타임라인에 넣지 않은 이유를 남긴다. "없었다"가 아니라 "이 로그 4개에는 기록이 없다"는 뜻이다.

- 설정 재적재 · 재시작: `ngx-err` 에 reload · signal · worker process 관련 줄 0건, `app` 에 기동 · 종료 관련 줄 0건.
- 전체 트래픽 급증: 분당 전체 요청 수는 구간 내내 34 ~ 58건으로 평소 범위(하루 평균 40건, 최대 68건)다. 달라진 것은 리포트 경로의 요청 수뿐이다(평소 분당 0 ~ 1건, 하루 중 14시대 외 합계 50건 → 14:38 ~ 14:51 에 171건).
- 응답 시간: `access` 에 응답 시간 필드가 없어 요청별 소요 시간과 요청 시작 시각은 확인할 수 없다.
- 14:37 이전의 경고: 14:07 ~ 14:36 에는 409 3건 외에 WARN · ERROR · 슬로 쿼리가 없다.

## 3. 가설과 검증

5xx 186건 · 누수 WARN 134건 · 풀 타임아웃(`Connection is not available…`) 49건은 **증상**이다. 이 절은 그 증상을 만들었을 수 있는 상류 조건을 계층별로 가설로 세우고, 각 가설을 무엇으로 틀렸다고 할 수 있는지만 적는다. **어느 가설이 맞는지는 이 절에서 판정하지 않는다.** 확인 방법의 명령도 아직 실행하지 않았다.

읽는 법:

- 지지 근거의 `a-connection-pool.md:<줄>` 은 이 문서(`docs/rca/a-connection-pool.md`)의 1 · 2절 줄번호이고, 괄호 안은 그 줄이 가리키는 원본 로그 줄이다.
- 로그 명령은 `incident-logs/a-connection-pool/` 에서, 코드 경로는 저장소 루트 기준이다.
- 가설들은 서로 배타적이지 않다. 둘 이상이 함께 성립할 수 있다.
- 운영 DB · 게이트웨이에서만 확인할 수 있는 값은 명령을 만들지 않고 "담당자에게 요청"으로 적었다.

### 3.1 가설 요약

| ID | 계층 | 가설 한 문장 |
|---|---|---|
| H1 | 애플리케이션 코드 | 학급 리포트 요청 1건이 DB 조회가 끝난 뒤에도 트랜잭션(커넥션)을 쥔 채 서명 계산을 돌려 커넥션 점유 시간이 길어졌고, 리포트가 동시에 5건 이상 겹치자 풀(max 5)이 비었다. |
| H2 | DB · 쿼리 | `submission` 조회가 인덱스를 쓰지 못해(또는 운영 테이블이 예상보다 훨씬 커서) 쿼리 자체가 느려졌고, 느린 쿼리가 커넥션 점유 시간을 늘렸다. |
| H3 | 트래픽 | 소수 클라이언트가 14:38 부터 리포트 경로를 반복 요청해 평소 분당 0 ~ 1건이던 리포트가 10 ~ 19건/분으로 늘었고, 이 동시 요청이 커넥션 5개를 모두 차지했다. |
| H4 | 인프라 · 게이트웨이 설정 | nginx 가 응답이 늦은 업스트림을 장애로 보고 일시 제외해, 리포트와 무관한 경로까지 502 로 번졌다(풀 고갈을 전 경로 장애로 키운 계층). |

### 3.2 H1 — 애플리케이션 코드: 트랜잭션 안의 긴 계산

| 항목 | 내용 |
|---|---|
| 1. 가설 | 학급 리포트 요청 1건이 DB 조회가 끝난 뒤에도 트랜잭션(커넥션)을 쥔 채 서명 계산을 돌려 커넥션 점유 시간이 길어졌고, 리포트가 동시에 5건 이상 겹치자 풀(max 5)이 비었다. |
| 2. 지지 근거 | a-connection-pool.md:186 (`app.log:1243`) 누수 WARN 134건의 스택에 모두 `ReportService…buildClassReport` 가 있다. · a-connection-pool.md:188 ~ 189 (`app.log:1258` · `1259`) 리포트 완료 로그 2밀리초 뒤에 커넥션이 반환된다. a-connection-pool.md:221 (`app.log:5266` · `5267`)도 같다. · a-connection-pool.md:196 (`app.log:1370`) 누수 WARN 에 나오는 커넥션이 5개 전부다. · a-connection-pool.md:204 (`app.log:1522`) `total=5, active=5, idle=0, waiting=4`. · a-connection-pool.md:205 (`app.log:1523`) 풀 타임아웃 49건 중 44건은 리포트가 아닌 경로다. · 코드: `modern/api/src/main/java/com/example/assignment/ReportService.java:41` `@Transactional`, `:71` 에서 `sign()` 호출, `:88` 20만 회 반복. 설정: `modern/api/src/main/resources/application.yml:14` · `15` · `18`(max 5 · 3000ms · 누수 임계 10초). |
| 3. 반증 조건 | (a) `sign()` 1회 소요 시간이 첫 사건의 뒤쪽 약 10초(3.6)에 한참 못 미치면 이 계산으로 점유 시간을 설명할 수 없다. 반대로 항상 10초 가까이 걸린다면 누수 WARN 이 없던 구간 밖 리포트(3.6)와 맞지 않는다. (b) 누수 WARN → 커넥션 반환 간격(134쌍)이 `sign()` 소요 시간과 무관하게 흩어져 있으면 틀리다. (c) 사고 당시 실행 중이던 코드가 이 저장소의 `ReportService` 와 다르면(서명 계산이 트랜잭션 밖) 틀리다. (d) 스택에 리포트 외 다른 진입점(`DistributionService` 등)에서 얻은 커넥션이 섞여 있으면 "리포트만의 문제"가 아니다. |
| 4. 확인 방법 | (a) `ReportServiceTest` 에서 `ReportService.sign(…)` 의 소요 시간을 임시로 재 본다(로컬 측정만, 커밋하지 않음). 운영 장비의 CPU 사양은 담당자에게 요청. (b) `grep -nE 'Connection leak detection triggered\|Previously reported leaked connection' app.log` 로 커넥션 ID 별 WARN · 반환 시각을 짝지어 간격 분포를 낸다. (c) 사고일(09-16) 배포 버전은 로그에 없다. 배포 이력을 담당자에게 요청하고 `ReportService.java:41 ~ 82` 와 대조한다. (d) `grep -c "ReportService.buildClassReport(ReportService.java" app.log`(프록시 프레임 제외) 와 `grep -c 'Apparent connection leak detected' app.log` 가 같은지, `grep -E '^\s+at com\.example\.' app.log \| sort \| uniq -c` 로 스택에 나오는 애플리케이션 프레임 종류를 본다. |

주의: Hikari 누수 WARN 의 스택은 **커넥션을 얻은 시점**의 스택이다(`app.log:1245` 최상단이 `HikariDataSource.getConnection`). 스택에 `buildClassReport` 가 있다는 것은 "그 메서드가 커넥션을 얻었다"까지만 말해 주며, 10초 동안 어디서 시간을 썼는지(쿼리인지 계산인지)는 말해 주지 않는다. 그래서 반증 조건을 스택이 아니라 소요 시간으로 잡았다.

### 3.3 H2 — DB · 쿼리: 느린 `submission` 조회

| 항목 | 내용 |
|---|---|
| 1. 가설 | `submission` 조회가 인덱스를 쓰지 못해(또는 운영 테이블이 예상보다 훨씬 커서) 쿼리 자체가 느려졌고, 느린 쿼리가 커넥션 점유 시간을 늘렸다. |
| 2. 지지 근거 | a-connection-pool.md:184 ~ 185 (`mariadb-slow.log:15` · `18` · `20` · `21`) 14:37:42 ~ 14:37:52 에 9.81초, 4행 반환에 1,284,310행 검사. 이날 3건 중 가장 길다. · a-connection-pool.md:186 (`app.log:1243`) 첫 누수 WARN 14:37:52.199 는 이 쿼리 시작(14:37:42)에서 10초 뒤이고 누수 임계가 10초(`application.yml:18`)다. · a-connection-pool.md:127 이상 구간에서 가장 이른 사건이 이 쿼리다. · 쿼리는 `ReportService.java:54` → `SubmissionRepository.java:8` 에서 나온다. |
| 3. 반증 조건 | (a) 누수 WARN 134건 가운데 같은 시각의 슬로 쿼리가 1건뿐이고, 그 이유가 슬로 로그 임계(`long_query_time`) 때문이 아니라 나머지 133건의 쿼리가 실제로 빨랐기 때문이면, 쿼리는 구간 전체를 설명하지 못한다. (b) 운영 DB 의 `EXPLAIN` 이 `idx_submission_distribution` 을 쓰고 예상 행 수가 수십이면 "인덱스 미사용"은 틀리다(그 경우 128만 행 검사가 따로 설명돼야 한다). |
| 4. 확인 방법 | (a) `sed -n 1,14p mariadb-slow.log` 로 1 · 2번 항목의 쿼리 · `Rows_examined` 를 3번과 비교. 운영 DB 의 `long_query_time` 값은 담당자에게 요청. (b) 스키마: `db/mariadb/init/01-schema.sql:105 ~ 115`(`:112` 에 `idx_submission_distribution` 이 정의돼 있다 — 가설과 **반대 방향**의 근거). 로컬에서 `docker compose --profile modern up -d` 뒤 `EXPLAIN select … from submission where distribution_id=5 order by student_id` · `SHOW INDEX FROM submission` · `SELECT COUNT(*) FROM submission`. 운영 DB 의 같은 값(인덱스 유무 · 행 수 · 통계 갱신 시각)은 담당자에게 요청. |

같은 계층에서 검토했지만 가설로 세우지 않은 것: **잠금 대기**. 슬로 쿼리의 `Lock_time` 이 0.000071초(`mariadb-slow.log:18`)이고, 같은 `distribution_id=5` 를 건드린 재배포(a-connection-pool.md:191, `app.log:1278`)는 14:38:29 로 쿼리 종료 뒤다.

### 3.4 H3 — 트래픽: 리포트 경로의 반복 요청

| 항목 | 내용 |
|---|---|
| 1. 가설 | 소수 클라이언트가 14:38 부터 리포트 경로를 반복 요청해 평소 분당 0 ~ 1건이던 리포트가 10 ~ 19건/분으로 늘었고, 이 동시 요청이 커넥션 5개를 모두 차지했다. |
| 2. 지지 근거 | a-connection-pool.md:175 (`nginx-access.log:17914`) 직전 19분 48초 동안 리포트 요청 0건. · a-connection-pool.md:187 (`nginx-access.log:18801`) 교사단말A 가 14:38:01 부터 같은 경로를 40건, 처음 10건은 22 ~ 31초 간격. · a-connection-pool.md:194 (`nginx-access.log:18881`) · a-connection-pool.md:197 (`nginx-access.log:18912`) 두 번째 · 세 번째 클라이언트 합류. · a-connection-pool.md:236 전체 요청 수는 평소 범위이고 달라진 것은 리포트 경로뿐(14:38 ~ 14:51 에 171건). · a-connection-pool.md:215 · 218 클라이언트의 마지막 요청(14:49:53 · 14:51:24) 뒤 증상이 끝난다. |
| 3. 반증 조건 | (a) 같은 클라이언트의 요청 간격이 "직전 응답 완료 직후"에 몰려 있으면(느려서 다시 누른 것) 반복 요청은 원인이 아니라 결과다. (b) 리포트 요청이 1건뿐이던 14:37:42 ~ 14:38:01 에 이미 커넥션 1개가 약 20초 잡혀 있었으므로(a-connection-pool.md:184 ~ 189), 요청 수가 평소 수준이어도 점유 시간만으로 풀이 비는 계산이 나오면 트래픽은 필요조건이 아니다. (c) 다른 날 · 다른 시간대에 같은 수준의 리포트 요청이 있었는데 증상이 없었다면 트래픽만으로는 설명되지 않는다. |
| 4. 확인 방법 | (a) `sed -n 18801,19381p nginx-access.log \| grep '/report ' \| awk '{print $1, $4, $9}'` 로 IP 별 완료 시각 · 상태 코드를 뽑아 간격을 계산한다(학생 식별자가 있는 URL 은 발췌 시 가린다). 고정 간격이면 자동 호출을 의심하고 `grep -rnE 'setInterval\|refetchInterval\|report' modern/web/src` 로 폴링 코드 유무를 본다. 리포트 화면(Referer `…/teacher/classes/3/report`)의 실제 프런트 코드는 담당자에게 요청. (b) H1 (a)의 `sign()` 소요 시간 × 분당 리포트 요청 수 ÷ 60 으로 평균 동시 점유 수를 계산해 5 와 비교한다. (c) `grep '/report ' nginx-access.log \| awk '{print substr($4,14,5)}' \| sort \| uniq -c \| sort -rn \| head` 로 하루 중 분당 리포트 요청 최댓값을 확인한다. 다른 날 로그는 담당자에게 요청. |

### 3.5 H4 — 인프라 · 게이트웨이 설정: 업스트림 일시 제외

| 항목 | 내용 |
|---|---|
| 1. 가설 | nginx 가 응답이 늦은 업스트림을 장애로 보고 일시 제외해, 리포트와 무관한 경로까지 502 로 번졌다(풀 고갈을 전 경로 장애로 키운 계층). |
| 2. 지지 근거 | a-connection-pool.md:207 (`nginx-error.log:6`) 첫 `upstream timed out` 14:41:06. · a-connection-pool.md:209 (`nginx-error.log:10`) 그 55초 뒤 `upstream server temporarily disabled` 첫 건, 합계 26건. · a-connection-pool.md:211 (`nginx-error.log:12`) `no live upstreams` 68건. 502 82건 중 50건이 리포트가 아닌 경로. · a-connection-pool.md:120 502 82 = `no live upstreams` 68 + `connect() failed` 14. · a-connection-pool.md:235 재시작 · reload 줄은 0건이다. |
| 3. 반증 조건 | (a) 업스트림이 여러 대이고 그중 일부만 제외된 것이면 "유일한 업스트림 제외"는 틀리다. (b) `connect() failed (111: Connection refused)` 14건은 앱이 연결 자체를 거부했다는 뜻이다. 이것이 nginx 설정이 아니라 앱 쪽 수용 한계(Tomcat 스레드 · accept 큐) 때문이면 502 의 일부는 이 가설 밖이다. (c) `temporarily disabled` 가 없는 분에도 비리포트 502 가 같은 비율로 나오면 제외 설정과 무관하다. (d) nginx 설정에 `max_fails=0` 이면 틀리다. |
| 4. 확인 방법 | (a) `sed -n 6,168p nginx-error.log \| grep -o 'upstream: "http://[^/]*' \| sort \| uniq -c` 로 업스트림 주소 분포를 본다(이 파일에는 Authorization 헤더 일부가 있으므로 줄 전체를 옮기지 않는다). (b) `grep -n 'connect() failed' nginx-error.log \| cut -c1-120` 의 시각을 `app.log` 같은 초의 항목 줄과 대조. 앱의 `server.tomcat.*` 설정은 `modern/api/src/main/resources/application.yml` 에서 확인. (c) `grep -E 'temporarily disabled\|no live upstreams' nginx-error.log \| awk '{print $1, $2, $3}' \| uniq -c` 로 시각 · 레벨(`[warn]` = 일시 제외, `[error]` = 업스트림 없음)을 같이 뽑아 초 단위 선후를 본다. (d) 저장소에 nginx 설정 파일이 없다. `nginx -T` 출력(업스트림 블록의 `max_fails` · `fail_timeout` · `proxy_read_timeout` · `proxy_next_upstream`)은 게이트웨이 담당자에게 요청. |

### 3.6 가설을 가르는 기준

- **첫 20초의 분해.** 슬로 쿼리 14:37:42 ~ 14:37:52(`mariadb-slow.log:15` · `20`) → 리포트 완료 · 커넥션 반환 14:38:01.90(`app.log:1258` · `1259`). 커넥션이 잡혀 있던 약 20초 가운데 앞 약 10초는 쿼리 시간과 겹치고, 뒤 약 10초는 슬로 로그에 대응하는 기록이 없다. 뒤 10초가 무엇인지가 H1 과 H2 를 가른다.
- **나머지 133건.** 누수 WARN 134건에 대응하는 슬로 쿼리는 1건이다. 나머지 133건의 점유 시간이 쿼리였는지 계산이었는지가 H1 과 H2 를 가르고, 요청당 점유 시간이 정해지면 H3 의 (b)가 계산된다.
- **구간 밖 리포트에는 누수 WARN 이 없다.** 14:10 ~ 14:18 의 리포트 5건(a-connection-pool.md:173 · 175)과 14:59 ~ 15:01 의 3건(a-connection-pool.md:229)은 같은 엔드포인트인데 누수 WARN 이 0건이다(a-connection-pool.md:62 ~ 65). 즉 평소 리포트 1건의 커넥션 점유는 누수 임계 10초에 못 미친다. 어느 가설이든 "왜 구간 안에서만 요청당 점유가 10초를 넘었는가"를 함께 설명해야 한다. H1 은 `sign()` 측정값을 이 사실과 대조하고, H2 는 `long_query_time` 이 낮은 값으로 확인되면 첫 1건에만 해당한다.
- **H4 는 범위가 다르다.** H1 ~ H3 은 "풀이 왜 비었는가", H4 는 "왜 502 가 전 경로로 번졌는가"에 대한 가설이다. H4 가 맞아도 풀 고갈의 설명은 되지 않는다.

### 3.7 가설로 세우지 않은 계층

- **배포 · 설정 변경**: `app.log` 에 기동 · 종료 줄, `nginx-error.log` 에 reload 줄이 0건이고(a-connection-pool.md:235), 이 로그 묶음에 배포 이력 파일이 없다. 저장소의 git 이력은 사고일(09-16) 이후인 09-22 에 시작하므로 사고 전 변경을 알려 주지 않는다. 가설을 세울 근거가 없어 비워 두며, 배포 · 설정 변경 이력(앱 · DB 인덱스 · nginx)을 담당자에게 요청하는 것으로 남긴다.

### 3.8 판정

3.1 의 가설 4개(H1 ~ H4)를 저장소의 코드 · 설정과 대조했다. 대조한 곳: `modern/api/src/main/resources/application.yml`, 로그 스택에 찍힌 `com.example.assignment` · `com.example.item` · `com.example.common` 클래스, `db/mariadb/init/`, `docker-compose.yml`, `modern/web/src/`. 요청문의 "대조할 곳" 칸이 비어 있어 이 범위로 정했다.

- 설정값은 파일에서 읽은 값만 썼다. 벤치마크 · 테스트는 실행하지 않았다(`sign()` 소요 시간은 재지 않았다).
- 로그는 3.2 ~ 3.5 의 확인 방법 가운데 `grep` · `awk` 집계로 되는 것만 추가로 돌렸다. 그 결과는 3.8.1 에 적었다.
- 운영 DB · nginx 설정 · 배포 이력은 저장소에 없어 확인하지 못했다.

#### 3.8.1 대조 중 로그에서 추가로 확인한 사실

| 번호 | 사실 | 출처 |
|---|---|---|
| F1 | 누수 WARN 134건은 모두 `ReportController.classReport` → `buildClassReport` 로 얻은 커넥션이다. 그 밖의 진입점(`ItemController` 26 · `UnitController` 14 · `DistributionController` 4)과 리포트 5건은 풀 타임아웃 49건의 스택에만 나온다. | `app.log` 의 `at com.example.` 프레임 집계: `ReportController.classReport(ReportController.java:22)` 139 = 134 + 5 |
| F2 | 구간 안에서 완료된 리포트 134건은 **전부** 누수 WARN 을 냈고, 커넥션 점유 시간(WARN 시각 − 10초 ~ 반환 INFO)은 15.5 ~ 21.5초, 평균 19.0초다. 15 ~ 20초 85건, 20 ~ 25초 49건. | `app.log` 누수 WARN 134 · 반환 INFO 134 · `built class report` 134(14:37 ~ 14:51) |
| F3 | 구간 밖에서 완료된 리포트 56건(하루 190건 − 134건)은 누수 WARN 이 0건이다. 즉 점유가 10초 미만이었다. | `app.log` `built class report` 190건, 누수 WARN 은 14:37:52 ~ 14:51:37 에만 |
| F4 | 슬로 로그에는 2.04초 · 2.32초짜리 쿼리도 기록돼 있다. 즉 기록 임계는 2.04초 이하다. 1 · 2번 항목은 `item` · `unit` 조회이고 `submission` 조회는 3번 1건뿐이다. | `mariadb-slow.log:4` · `7` · `11` · `14` · `18` · `21` |
| F5 | nginx 업스트림은 2대다. `upstream timed out` 55건과 `temporarily disabled` 26건은 모두 `API-1:8080`, `connect() failed (Connection refused)` 14건은 모두 `API-2:8080` 이다. `API-2` 는 `app.log` · `nginx-access.log` · `mariadb-slow.log` 에 한 번도 나오지 않는다. | `nginx-error.log:6` · `10` · `11` 외, `upstream: "…"` 필드 집계 |
| F6 | 구간 안 리포트 요청 171건의 응답: 200 79 · 500 5 · 502 32 · 504 55. | `nginx-access.log:18801 ~ 19381` |
| F7 | 구간 안에서 앱이 완료한 리포트 134건(F2)은 리포트 응답 200 79 + 504 55(F6)와 건수가 같다. 건수로 보면 504 는 앱에서 끝까지 처리됐지만 nginx 가 먼저 끊은 요청이고, 502 32 · 500 5 는 완료 로그가 없는 요청이다. nginx 의 읽기 타임아웃 값은 로그에 없다. | F2 · F6 건수 대조 |

#### 3.8.2 가설별 판정

| ID | 판정 | 근거(파일:줄번호) |
|---|---|---|
| H1 | **코드 · 설정이 가설을 지지한다** (구조는 확인, 소요 시간은 미확인) | ① `ReportService.java:41` `@Transactional` 이 `:42 ~ 82` 메서드 전체를 감싸고, DB 조회(`:43` · `:46` · `:54`)가 끝난 뒤 `:71` 에서 `sign()` 을 부른다. `sign()` 은 `:88` 에서 `SIGNATURE_ROUNDS`(`:23`, 200,000)회 반복한다. `:69 ~ 70` 주석도 같은 내용이다. ② `application.yml:22` `open-in-view: false` 이므로 커넥션은 트랜잭션에만 묶이고 메서드가 끝날 때 반환된다. 마지막 문장인 `:73` 로그 2밀리초 뒤에 커넥션이 반환되는 로그(`app.log:1258` · `1259`, `5266` · `5267`)와 맞는다. ③ `application.yml:14` max 5 · `:15` 3000ms · `:18` 10000ms 가 로그의 `total=5` · `timed out after 3000ms` (`app.log:1522`) · 첫 누수 WARN 의 10초 간격과 일치한다. ④ `ReportController.java:22` 가 서비스를 그대로 부르며 캐시 · 동시 실행 제한이 없다. ⑤ F1: 누수 커넥션은 전부 이 경로에서 나왔다. |
| H2 | **코드 · 설정이 가설을 반박한다** (운영 DB 상태는 찾지 못함) | ① 쿼리는 `SubmissionRepository.java:8` 의 파생 쿼리이고 슬로 로그의 SQL(`mariadb-slow.log:21`)과 같은 형태다. ② `db/mariadb/init/01-schema.sql:112` 에 `idx_submission_distribution (distribution_id)` 가 있다. 저장소 스키마대로라면 이 조건은 인덱스를 쓴다. ③ `db/mariadb/init/02-seed.sql:143` 의 `submission` 시드는 30행이다. 1,284,310행 검사(`mariadb-slow.log:18`)는 저장소 스키마 · 시드로 설명되지 않는다. ④ `docker-compose.yml:21 ~ 23` 에 슬로 로그 관련 옵션이 없어 운영 임계값은 파일로 확인할 수 없다(F4 는 로그에서 얻은 값). ⑤ F2 · F4: 점유 15.5 ~ 21.5초짜리 134건 중 슬로 쿼리가 대응하는 것은 1건이다. |
| H3 | **관련 코드를 찾지 못했다** | ① `modern/web/src/api/` 에는 `client.ts` · `items.ts` · `types.ts` 뿐이고 `hooks/` · `components/` 에 리포트 화면 · 훅이 없다. `setInterval` · `refetchInterval` · `/report` 검색 결과 0건. ② 로그의 Referer 는 `…/teacher/classes/3/report` 로, 이 저장소에 없는 화면이다. ③ 서버 쪽에는 요청 수를 제한하는 코드가 없다(`ReportController.java:20 ~ 23`). |
| H4 | **관련 코드를 찾지 못했다** | ① 저장소에 nginx 설정 파일이 없다(`find` 결과 로그 파일뿐). ② `application.yml:28 ~ 29` 는 `server.port: 8080` 만 정하고 `server.tomcat.*` 가 없어, 앱의 수용 한계는 파일에 적힌 값이 없다. ③ F5: 로그로는 업스트림이 2대이고 연결 거부는 `API-2` 에서만 났다는 것까지 확인된다. 3.5 의 반증 조건 (b)("앱이 연결을 거부")는 해당하지 않는다. `API-1` 이 거부한 건은 없다. ④ 3.5 의 반증 조건 (a)는 로그로 성립한다. 일시 제외된 것은 2대 중 `API-1` 뿐이고, 같은 초(`nginx-error.log:10` · `11`)에 `API-2` 가 연결을 거부해 `no live upstreams` 가 됐다. 즉 502 확대에는 "`API-2` 가 요청을 받지 못하는 상태였다"는 별도 사실이 필요하다. |

#### 3.8.3 채택하는 가설

**H1 (애플리케이션 코드)을 채택한다.** `ReportService.buildClassReport` 가 DB 조회 뒤의 서명 계산까지 트랜잭션에 넣어 리포트 요청 1건이 응답이 끝날 때까지 커넥션 1개를 쥐고 있고, 풀이 5개뿐이라 리포트 요청이 겹치자 다른 경로가 3초 안에 커넥션을 얻지 못했다.

풀 고갈은 "요청당 점유 시간 × 동시 요청 수"가 5 를 넘을 때 생긴다. 커넥션을 실제로 쥔 것은 완료된 리포트(F7)이고 그 수는 14:41 ~ 14:50 에 8 ~ 15건/분(a-connection-pool.md:188)이다. 여기에 F2 의 평균 점유 19초를 곱하면 평균 동시 점유가 2.5 ~ 4.8개다. 평균이 풀 크기 5 에 가까워 요청이 몰리는 순간 5 를 넘고, 실제로 14:40:52 에 `active=5, waiting=4` 였다(`app.log:1522`). 점유 시간을 요청 처리 시간 전체로 늘려 놓는 것이 코드의 구조이므로 이것을 원인으로, 요청 증가(H3)는 촉발 조건으로 본다.

#### 3.8.4 기각한 가설과 사유

| ID | 기각 사유 | 남는 역할 |
|---|---|---|
| H2 | 슬로 쿼리가 대응하는 것은 134건 중 첫 1건뿐이고, 기록 임계가 2.04초 이하(F4)이므로 나머지 133건의 쿼리는 그보다 빨랐다. 그런데 그 133건도 15.5초 이상 커넥션을 쥐었다(F2). 저장소 스키마에는 해당 인덱스가 있다. | 14:37:42 의 9.81초 쿼리 1건은 설명되지 않은 채 남는다(4행 반환에 128만 행 검사). 이상 구간의 첫 사건이므로 운영 DB 확인 대상이다. |
| H3 | 리포트 10 ~ 19건/분은 절대량이 작고 전체 트래픽은 평소 범위다(a-connection-pool.md:236). 요청당 점유가 구간 밖처럼 10초 미만(F3)이고 커넥션이 조회 직후 반환됐다면 이 요청 수로 풀 5개가 비지 않는다. 또 클라이언트가 1대뿐이던 14:37:42 ~ 14:38:20 에 이미 점유가 약 20초였다(a-connection-pool.md:184 ~ 190). | 촉발 조건. 왜 3대가 같은 경로를 반복 요청했는지(자동 폴링인지, 느려서 다시 누른 것인지)는 코드가 없어 판정하지 못했다. |
| H4 | 풀이 왜 비었는지에 대한 설명이 아니다(3.6). 500 49건 · 504 55건은 nginx 제외 설정 없이도 생긴다. | 확대 요인. 502 82건(리포트 외 경로 50건)은 `API-1` 일시 제외 + `API-2` 연결 거부(F5)로 생겼다. `API-2` 가 왜 연결을 거부했는지는 이 로그에 없다. |

#### 3.8.5 확신 수준: 중간

올리는 근거:

- 누수 커넥션 134건이 전부 한 메서드에서 나왔고(F1), 그 메서드가 계산이 끝날 때까지 커넥션을 쥐는 구조임을 코드에서 직접 읽었다.
- 풀 크기 · 타임아웃 · 누수 임계 세 값이 설정 파일과 로그에서 일치한다.
- 다른 경로의 500 은 전부 커넥션 획득 실패이며 그 경로들의 코드 문제가 아니다(F1).

낮추는 근거:

- **구간 안팎의 차이를 코드로 설명하지 못한다.** `buildClassReport` 는 요청마다 같은 조회와 같은 횟수의 서명 계산을 한다. 로그의 `3 distributions, 10 submissions` 도 구간 안팎이 같다. 그런데 점유 시간은 구간 밖 10초 미만(F3), 구간 안 15.5 ~ 21.5초(F2)다. 클라이언트가 1대뿐이던 처음 두 요청도 약 20초였으므로 동시 요청에 따른 경합만으로는 설명되지 않는다.
- `sign()` 1회가 실제로 몇 초인지 재지 않았다. 코드로 확인한 것은 "계산이 트랜잭션 안에 있다"까지이고, "그 계산이 15초 이상 걸린다"는 확인하지 않았다.
- 사고 당시 배포된 코드가 이 저장소와 같은지 알 수 없다(git 이력은 09-22 부터).
- 첫 사건인 9.81초 쿼리의 원인을 모른다.

확신을 높이려면 필요한 것:

1. `sign()` 소요 시간 측정(로컬 · 운영 장비 사양). F2 의 15.5 ~ 21.5초, F3 의 10초 미만과 대조한다.
2. 14:37 ~ 14:52 의 앱 호스트(`API-1`) CPU · GC 지표와 DB 서버 지표. 구간 안에서만 요청 처리가 느려진 이유를 본다.
3. 운영 DB 의 `submission` 행 수 · 인덱스 · `long_query_time`.
4. nginx 설정(`nginx -T`): `proxy_read_timeout`(F7 로 보면 점유 시간 15.5 ~ 21.5초 범위와 겹치는 값인지), `max_fails` · `fail_timeout`, `API-2` 의 `backup` · `down` 지정 여부. `API-2` 는 14:42:01 이전 로그에 나오지 않는다.
5. 09-16 의 배포 이력과 리포트 화면의 프런트 코드.
6. `API-2` 의 `app.log`. 이 로그 묶음의 `app.log` 는 `API-1` 한 대의 것으로 보이며(F5), 두 번째 호스트의 로그는 수집 범위에 없다.

## 4. 원인

채택한 가설은 H1(3.8.3)이다. 문장 번호 C1 ~ C8 · B1 ~ B4 는 5절의 근거와 짝이다.

### 4.1 직접 원인 (인과 순서)

- **C1.** `buildClassReport` 는 메서드 전체가 트랜잭션이고, DB 조회가 끝난 뒤의 서명 계산(`sign()`, 20만 회 반복)까지 그 안에서 돈다. 그래서 리포트 요청 1건이 응답이 끝날 때까지 커넥션 1개를 쥔다.
- **C2.** 14:37:42 학급 3 리포트 요청에서 `submission` 조회가 9.81초 걸렸고, 이 커넥션은 14:38:01 까지 약 20초 잡혀 있었다. 10초 시점에 첫 누수 WARN 이 나왔다.
- **C3.** 14:38:01 부터 같은 클라이언트가 리포트를 22 ~ 31초 간격으로 다시 요청했고, 14:39:54 · 14:40:43 에 두 클라이언트가 더해져 리포트 요청이 분당 10 ~ 19건이 됐다.
- **C4.** 이 구간에서 완료된 리포트 134건은 전부 커넥션을 15.5 ~ 21.5초(평균 19.0초) 쥐었다. 완료 기준 8 ~ 15건/분이므로 평균 동시 점유가 2.5 ~ 4.8개로, 요청이 몰리면 풀 크기 5 를 넘는 수준이었다.
- **C5.** 14:40:52 풀이 `active=5, idle=0, waiting=4` 가 됐다. 리포트가 아닌 경로(단원 · 문항 · 배포 조회)가 3초 안에 커넥션을 얻지 못해 500 이 됐다(49건 중 44건).
- **C6.** 리포트 자체는 끝까지 처리됐지만 nginx 가 먼저 끊어 504 가 됐다(55건, 전부 리포트 경로).
- **C7.** 14:42:01 nginx 가 `API-1` 을 일시 제외했고, 다른 업스트림 `API-2` 는 연결을 거부했다. 받을 업스트림이 없어 502 가 났다(82건 중 50건이 리포트 외 경로).
- **C8.** 14:49:53 · 14:51:24 에 반복 요청이 멈췄고, 14:51:49 마지막 커넥션이 반환되면서 정상으로 돌아왔다. 재시작 · 설정 변경 없이 복구됐다.
- **단,** 구간 밖의 리포트 56건은 누수 WARN 이 없었다(점유 10초 미만). 같은 코드가 이 구간에서만 15초 이상 걸린 이유는 확인하지 못했다(9절).

### 4.2 배경 원인 (직접 원인을 가능하게 한 조건)

- **B1.** 풀이 5개 · 대기 3초이고 모든 엔드포인트가 한 풀을 같이 쓴다. 한 엔드포인트가 커넥션을 다 쥐면 나머지가 바로 실패한다.
- **B2.** 리포트 엔드포인트에 동시 실행 제한 · 캐시가 없다. 같은 학급 리포트를 다시 요청하면 매번 처음부터 계산한다.
- **B3.** 두 번째 업스트림이 요청을 받지 못하는 상태였다. 한 대가 느려지자 받을 곳이 없었다.
- **B4.** 누수 WARN 은 첫 500 보다 3분 먼저(14:37:52) 나왔지만 경보로 이어지지 않았다.

## 5. 근거 로그 줄

로그는 `incident-logs/a-connection-pool/`, 코드 · 설정은 `modern/api/src/main/` 아래다. 발췌는 80자 이내이고 `…` 는 생략이다.

| 문장 | 출처(파일:줄번호) | 발췌 |
|---|---|---|
| C1 | `java/com/example/assignment/ReportService.java:41` · `:71` | `@Transactional` / `String signature = sign(payload.toString());` |
| C1 | `resources/application.yml:22` | `open-in-view: false` |
| C1 | `app.log:1258` · `1259` | `built class report for class 3…` 2밀리초 뒤 `Previously reported leaked connection…` |
| C2 | `mariadb-slow.log:18` · `21` | `Query_time: 9.812417 … Rows_sent: 4  Rows_examined: 1284310` |
| C2 | `app.log:1243` | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3…` |
| C3 | `nginx-access.log:18801` · `18881` · `18912` | `"GET /api/classes/3/report HTTP/1.1" 200 266` |
| C4 | `app.log:1243` · `1259` ~ `5251` · `5267` (첫 쌍 ~ 마지막 쌍, 집계는 3.8.1 F2) | 누수 WARN 134 = 반환 INFO 134 = `built class report` 134 |
| C5 | `app.log:1522` | `…request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` |
| C5 | `app.log:1523` · `nginx-access.log:18918` | `unhandled exception on /api/units/M6-2/items` / `…HTTP/1.1" 500 162` |
| C5 | `resources/application.yml:14` · `15` | `maximum-pool-size: 5` / `connection-timeout: 3000` |
| C6 | `nginx-error.log:6` · `nginx-access.log:18933` | `upstream timed out (110: Connection timed out) while reading response header…` |
| C7 | `nginx-error.log:10` | `upstream server temporarily disabled while reading response header from upstream` |
| C7 | `nginx-error.log:11` · `12` | `connect() failed (111: Connection refused)…` / `no live upstreams while connecting…` |
| C8 | `nginx-access.log:19307` · `19364` · `app.log:5266` · `5267` | `Previously reported leaked connection …Connection@6e8f14d3 on thread …exec-10…` |
| 단서 | `app.log:1148` · `1175` · `5283` | `built class report for class 3: 3 distributions, 10 submissions` (누수 WARN 없음) |
| B1 | `resources/application.yml:14` · `15` | `maximum-pool-size: 5` / `connection-timeout: 3000` |
| B2 | `java/com/example/assignment/ReportController.java:20 ~ 23` | `return reportService.buildClassReport(id);` |
| B3 | `nginx-error.log:11` (14건 모두 `API-2`, 3.8.1 F5) | `connect() failed (111: Connection refused) while connecting to upstream` |
| B4 | `app.log:1243` (14:37:52) · `app.log:1522` (14:40:52) | `Connection leak detection triggered…` / `…request timed out after 3000ms…` (간격 3분 0초) |

## 6. 수정안

지금 적용할 수정만 적는다. 제안이며 코드는 고치지 않았다.

| 번호 | 파일 | 무엇을 어떻게 |
|---|---|---|
| 1 | `modern/api/src/main/java/com/example/assignment/ReportService.java` | `buildClassReport` 의 `@Transactional`(`:41`)을 뗀다. 이 메서드는 조회 결과를 받아 `sign()` 을 부르고 `ClassReport` 를 만드는 일만 한다. `:69 ~ 70` 의 TODO 주석을 지운다. |
| 2 | 같은 패키지에 새 서비스(예: `ReportQueryService`) | `:43 ~ 67` 의 조회 · 집계를 옮기고 `@Transactional(readOnly = true)` 를 붙인다. 엔티티가 아니라 record(학급 id · 이름 · 배포 수 · 제출 수 · 평균 · 서명 대상 문자열)를 돌려준다. 같은 클래스 안의 메서드로 나누면 프록시를 거치지 않아 트랜잭션이 걸리지 않으므로 별도 빈으로 둔다. |
| 3 | `modern/api/src/test/java/com/example/assignment/` | 새 서비스 메서드의 테스트와, `sign()` 이 조회 서비스 호출 뒤에 불리는지 확인하는 `ReportServiceTest` 케이스를 추가한다. |
| 4 | nginx 업스트림 설정(저장소에 없음) | `API-2` 가 왜 연결을 거부하는지 확인하고, 복구하거나 업스트림에서 뺀다. |

- 풀 크기 · 타임아웃(`application.yml:14` · `15`)은 바꾸지 않는다. 풀을 늘리면 같은 구조가 더 늦게 터질 뿐이다. 이 값의 변경은 사전 승인 대상이기도 하다.
- 이 수정은 "리포트가 느려도 다른 경로의 커넥션을 빼앗지 않게" 한다. 리포트 자체가 15초 이상 걸린 이유(9절 1번)는 고치지 못하므로, 수정 뒤에도 리포트의 504 는 남을 수 있다.
- 적용 뒤 확인: `cd modern/api && ./gradlew test`, 그리고 리포트를 연속 요청했을 때 누수 WARN 이 0건인지 본다.

## 7. 재발 방지

같은 유형("한 엔드포인트가 커넥션을 오래 쥐어 풀 전체가 마른다")을 구조적으로 막는 장치다.

| 번호 | 장치 | 막는 것 |
|---|---|---|
| 1 | 트랜잭션 경계 규칙을 CI 에서 검사한다. `@Transactional` 메서드 안의 외부 호출 · 반복 계산 금지는 이미 팀 규칙(CLAUDE.md 3절)이지만 사람이 지켜야만 한다. 아키텍처 테스트나 `convention-check` 로 머지 전에 걸러 낸다(테스트 라이브러리 추가는 승인 대상). | C1 |
| 2 | 리포트처럼 무거운 엔드포인트에 동시 실행 상한을 둔다(예: 풀의 절반 이하). 상한을 넘으면 기다리게 하지 않고 바로 거절한다. | B1 · B2 |
| 3 | 같은 학급 리포트 결과를 짧게 캐시하고, 화면에서는 응답이 오기 전의 재요청을 막는다. | B2 · C3 |
| 4 | nginx 에서 리포트 경로에 클라이언트별 요청 제한을 건다. | C3 |
| 5 | nginx 업스트림에 상태 확인(health check)을 두어 응답하지 않는 서버를 미리 빼고, 살아 있는 업스트림이 1대뿐이면 배포를 진행하지 않는다. | B3 |
| 6 | 릴리스 전에 운영과 같은 풀 설정(max 5)으로 리포트 동시 요청 시험을 돌려, 다른 경로의 응답이 유지되는지 본다. | C4 · C5 |

## 8. 모니터링 항목

첫 500 은 14:40:52 다. "이번 발화 시각"은 2절 타임라인에 임계값을 대입한 것이다.

| 번호 | 지표 | 임계값(제안) | 이번 발화 시각 | 첫 500 대비 |
|---|---|---|---|---|
| 1 | `app.log` 누수 WARN(`Connection leak detection triggered`) | 1건 이상(평소 0건) | 14:37:52 | 3분 0초 전 |
| 2 | 같은 WARN 의 반복 | 5분 안에 3건 | 14:38:51 | 2분 1초 전 |
| 3 | 슬로 쿼리 `Query_time` | 5초 이상(평소 최대 2.3초) | 14:37:52 | 3분 0초 전 |
| 4 | 리포트 경로 요청 수 | 분당 3건 이상(평소 0 ~ 1건) | 14:39:00(14:38 분 집계) | 1분 52초 전 |
| 5 | 리포트 처리 시간 | 5초 이상 | 14:38:01 로 추정(첫 요청이 약 20초) | 2분 51초 전 |
| 6 | Hikari `active` = 최대값 또는 `pending` 1 이상 | 30초 지속 | 로그로는 특정 불가. 늦어도 14:40:52 | 불명 |
| 7 | 5xx 응답 수 | 분당 5건 이상(평소 0건) | 14:41 분(500 6 + 504 3) | 이후(사후 지표) |
| 8 | nginx `no live upstreams` · 업스트림 연결 거부 | 1건 이상 | 14:42:01 | 1분 9초 뒤 |

- 1 ~ 5 번이 선행 지표다. 이번에는 1 · 3 번이 가장 빨랐다.
- 5 · 6 번은 지금 로그에 값이 없다. 9절의 로그를 남겨야 쓸 수 있다.
- 8 번의 업스트림 상태 점검이 평소에 돌고 있었다면 `API-2` 문제는 장애 전에 드러났을 수 있다. 언제부터 내려가 있었는지는 로그에 없다.

## 9. 확인하지 못한 것

| 번호 | 판단하지 못한 것 | 이유 | 다음을 위해 남길 로그 · 자료 |
|---|---|---|---|
| 1 | 같은 코드인데 구간 안에서만 리포트가 15.5 ~ 21.5초 걸린 이유 | 요청 처리 시간 · 호스트 자원 기록이 없다. `sign()` 소요 시간도 재지 않았다 | 엔드포인트별 처리 시간 로그, 앱 호스트 CPU · GC 지표, `sign()` 측정값 |
| 2 | 14:37:42 쿼리가 4행 반환에 128만 행을 검사한 이유 | 운영 DB 의 행 수 · 인덱스 · 실행 계획을 볼 수 없다. 저장소 스키마에는 인덱스가 있고 시드는 30행이다 | 슬로 로그에 실행 계획 포함, 운영 DB 의 `submission` 행 수 · 인덱스 |
| 3 | 클라이언트 3대가 리포트를 반복 요청한 이유(자동 폴링인지 재시도인지) | 리포트 화면 코드가 저장소에 없고, `nginx-access.log` 에 응답 시간 · 요청 시작 시각이 없다 | `nginx-access.log` 에 `$request_time` · `$upstream_response_time` · `$upstream_addr` |
| 4 | `API-2` 가 연결을 거부한 이유와 시작 시점 | 이 호스트의 로그가 수집 범위에 없고 nginx 설정이 저장소에 없다 | `API-2` 의 `app.log`, `nginx -T` 출력, 업스트림 상태 점검 기록 |
| 5 | nginx 읽기 타임아웃 값과 업스트림 제외 조건 | 설정 파일이 없다 | `nginx -T` 출력(`proxy_read_timeout` · `max_fails` · `fail_timeout`) |
| 6 | 풀이 가득 찬 정확한 시각 | 풀 상태는 타임아웃 예외 메시지에만 찍힌다 | Hikari 지표(`active` · `idle` · `pending`)를 주기적으로 기록 |
| 7 | 사고 당시 배포된 코드가 이 저장소와 같은지 | 로그에 버전 · 기동 줄이 없고 git 이력은 09-22 부터다 | 배포 이력, 기동 로그에 빌드 버전 |

마스킹 적용: IP 주소 35건(역할 별칭 33 · `x` 치환 2) · 학생 식별자 1건 · 이메일 0건 · 토큰류 0건 · 비밀번호 · 접속 문자열 0건
