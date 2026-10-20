# 전사 개발자 규칙 후보 — chanjun (주 스택: python backend)

## 0. 기준

- 대상: `.claude/settings.json`(permissions · hooks)과 `hooks/block-dangerous.mjs` · `hooks/secret-scan.mjs`, Hook 실행기 `scripts/hook-node.sh`. 기존 파일은 수정하지 않았다.
- 적용 범위 표기: 어느 스택에서든 뜻이 같으면 **공통**, 특정 빌드 · 패키지 도구에만 해당하면 **스택 코드**. 이 저장소에는 스택 코드의 공식 목록이 없어서(`data-infra` 등 문자열을 검색했으나 정의 없음) 도구 이름을 괄호에 적었다. 공식 코드가 정해지면 치환한다.
- 근거 표기: `settings.json:줄` / `hooks/<파일>:줄`.

## 1. 현재 규칙 정리

| 규칙 | 종류 | 적용 범위 | 막는 사고 또는 허용 이유 |
|---|---|---|---|
| `Bash(rm -rf *)` 거부 (`settings.json:5`) | deny | 공통 | 작업 디렉터리 · 홈 전체 삭제. 리터럴 `rm -rf` 만 막으므로 아래 Hook 이 변형을 보완한다 |
| `rm` 의 `-fr` · `-r -f` · `-Rf` · `--recursive --force` 변형 (`block-dangerous.mjs:20`) | Hook | 공통 | deny 글롭을 플래그 순서 바꿔 우회하는 재귀 강제 삭제 |
| `DROP TABLE` · `DROP DATABASE` (`block-dangerous.mjs:21`) | Hook | 공통 | 스키마 · 데이터 영구 삭제. deny 목록에는 이 규칙이 없고 Hook 만 막는다 |
| `Bash(sudo *)` 거부 (`settings.json:6`) | deny | 공통 | 권한 상승으로 시스템 설정 · 다른 사용자 파일 변경 |
| `git push --force *` · `git push -f *` 거부 (`settings.json:7-8`) | deny | 공통 | 공유 브랜치 이력 덮어쓰기 · 동료 커밋 유실 |
| `git reset --hard *` 거부 (`settings.json:9`) | deny | 공통 | 커밋하지 않은 작업 소실 |
| `Bash(*prod-db*)` · `Bash(*prod.db*)` 거부 (`settings.json:10-11`) | deny | 공통 | 운영 DB 로의 조회 · 변경 접근 |
| 명령에 `prod-db` · `prod.db` 포함 (`block-dangerous.mjs:22`) | Hook | 공통 | 위 deny 의 이중 방어(셸 래퍼 · 문자열 조합으로 deny 매칭이 빗나가는 경우) |
| `curl *` · `wget *` 거부 (`settings.json:12-13`) | deny | 공통 | 코드 · 데이터 · 토큰의 외부 전송, 출처 불명 스크립트 내려받기 |
| `Read(./.env)` · `Read(./.env.*)` 거부 (`settings.json:14-15`) | deny | 공통 | 환경 변수 파일의 비밀값이 모델 컨텍스트로 유입 |
| `Read(./secrets/**)` · `Read(**/*.pem)` 거부 (`settings.json:16-17`) | deny | 공통 | 비밀 디렉터리 · 개인키 노출 |
| `Read(~/.ssh/**)` · `Read(~/.aws/**)` 거부 (`settings.json:18-19`) | deny | 공통 | SSH 키 · 클라우드 자격증명 노출 |
| `Edit(./.env)` · `Edit(./.env.*)` · `Edit(./secrets/**)` 거부 (`settings.json:20-22`) | deny | 공통 | 비밀 파일을 AI 가 만들거나 덮어쓰는 사고 |
| `mcp__github__delete_file` · `mcp__github__merge_pull_request` 거부 (`settings.json:23-24`) | deny | 공통 | 원격 저장소 파일 삭제 · 리뷰 없는 머지 |
| `git push *` (`settings.json:27`) | ask | 공통 | 푸시 대상 브랜치 · 커밋을 사람이 확인 |
| `docker compose down *` (`settings.json:32`) | ask | 공통 | 로컬 컨테이너 · 볼륨 내려가며 개발 DB 상태 소실 |
| `docker compose --profile *` (`settings.json:33`) | ask | 공통 | 프로필별 서비스 기동(DB · 외부 연동)을 사람이 인지하고 시작 |
| `mcp__github__create_pull_request` · `mcp__github__push_files` (`settings.json:34-35`) | ask | 공통 | 원격에 변경을 올리는 외부 쓰기 |
| `mcp__dbhub` · `mcp__dbhub-mssql` (`settings.json:36-37`) | ask | 공통 | DB MCP 로 실행되는 SQL 을 사람이 확인(운영 DSN 오접속 방지) |
| `npm install *` · `npm i *` · `npm ci *` (`settings.json:28-30`) | ask | 스택 코드(npm) | 의존성 변경 = 공급망 · 라이선스 승인 대상 (CLAUDE.md §3) |
| `pip install *` (`settings.json:31`) | ask | 스택 코드(python/pip) | 미승인 패키지 설치 = 공급망 위험. 설치 시점에 사람이 패키지명을 확인 |
| `npm test` · `npm run test|lint|typecheck|build` (`settings.json:40-44`) | allow | 스택 코드(npm) | 부작용이 로컬 산출물뿐인 검증 명령이라 프롬프트 없이 허용 |
| `./gradlew test|check|build` (`settings.json:45-47`) | allow | 스택 코드(gradle) | 위와 같음 |
| `git status|diff|log` (`settings.json:48-50`) | allow | 공통 | 읽기 전용 |
| `git add *` · `git commit *` (`settings.json:51-52`) | allow | 공통 | 로컬 이력만 변경(원격 반영은 push ask). 커밋 직전에 secret-scan Hook 이 걸린다는 전제 |
| `WebFetch(domain:docs.spring.io|docs.gradle.org)` (`settings.json:53-54`) | allow | 스택 코드(gradle/spring) | 공식 문서 조회 |
| `WebFetch(domain:developer.mozilla.org)` (`settings.json:55`) | allow | 스택 코드(web) | 공식 문서 조회 |
| `mcp__github__get_file_contents|list_pull_requests|pull_request_read|list_issues` (`settings.json:56-59`) | allow | 공통 | GitHub 읽기 전용 조회 |
| `mcp__item-bank__search_items` (`settings.json:60`) | allow | 스택 코드(item-bank 저장소 전용) | 이 저장소의 읽기 전용 문항 검색 MCP |
| `git commit` 직전 스테이징 diff 의 AWS 키(`AKIA…`) · `password = '…'` · PEM 개인키 (`secret-scan.mjs:36-40`) | Hook | 공통 | 시크릿이 커밋 이력에 영구히 남는 사고. 값은 출력하지 않고 패턴명 · 건수만 보고(`secret-scan.mjs:42`) |
| node 를 못 찾으면 모든 Bash 호출 차단 (`scripts/hook-node.sh:14-17`) | Hook | 공통 | node 가 없는 셸에서 Hook 이 조용히 꺼져 규칙이 무력화되는 것(fail-closed) |

## 2. 추가 제안 (python backend 에 필요한데 지금 없는 규칙)

| 규칙 | 종류 | 적용 범위 | 막는 사고 또는 허용 이유 |
|---|---|---|---|
| `Bash(pip3 install *)` · `Bash(python -m pip install *)` · `Bash(python3 -m pip install *)` · `Bash(uv add *)` · `Bash(uv pip install *)` · `Bash(poetry add *)` · `Bash(pipenv install *)` | ask | 스택 코드(python) | 현재 `pip install *` 하나뿐이라 `pip3` · `python -m pip` · uv · poetry 로 같은 설치가 프롬프트 없이 실행된다 |
| `Bash(pytest *)` · `Bash(python -m pytest *)` · `Bash(ruff check *)` · `Bash(ruff format --check *)` · `Bash(mypy *)` | allow | 스택 코드(python) | npm · gradlew 에 해당하는 검증 명령이 python 에는 허용 목록에 없어 매번 프롬프트가 뜬다 |
| `Bash(uv sync *)` · `Bash(poetry install *)` · `Bash(pip install -r *)` (lock 기준 설치) | ask | 스택 코드(python) | lock 파일대로 설치하는 명령도 외부 패키지를 받는다. npm ci 와 같은 취급 |
| `Read(**/.env)` · `Read(**/.env.*)` · `Edit(**/.env)` · `Edit(**/.env.*)` (예외 `.env.example` 은 허용) | deny | 공통 | 현재 `./.env` 는 프로젝트 루트만 매칭한다. `app/.env`, `services/api/.env` 같은 하위 경로는 열린다 |
| `Read(~/.config/gcloud/**)` · `Read(~/.kube/config)` · `Read(~/.pgpass)` · `Read(~/.my.cnf)` · `Read(~/.netrc)` · `Read(~/.pypirc)` | deny | 공통 | 클라우드 · DB · PyPI 업로드 토큰 노출. 현재는 `.ssh` · `.aws` 만 막는다 |
| `Bash(cat *.env*)` · `Bash(* ~/.aws/*)` · `Bash(printenv*)` · `Bash(env)` | deny | 공통 | `Read` deny 는 Bash 의 `cat` 으로 우회된다. 같은 파일을 Bash 로도 막아야 한다 |
| `Bash(git push *--force*)` · `Bash(git push * -f*)` (플래그 위치 무관) | deny | 공통 | 현재 deny 는 `git push --force ...` 처럼 플래그가 push 바로 뒤에 올 때만 매칭한다. `git push origin main --force` 는 ask 로만 걸린다 |
| `Bash(git clean -f*)` · `Bash(git checkout -- *)` · `Bash(git branch -D *)` · `Bash(git push * --delete *)` | ask | 공통 | `reset --hard` 와 같은 작업 소실 · 원격 브랜치 삭제인데 목록에 없다 |
| Hook: `TRUNCATE` · `DROP SCHEMA` · `DELETE FROM`/`UPDATE` 의 `WHERE` 누락 | Hook | 공통 | Hook 이 `DROP TABLE|DATABASE` 만 본다. `psql -c "TRUNCATE …"` 는 통과한다 |
| Hook: 운영 DB 패턴 확장 (`prod-`, `-prod`, `prd`, `.rds.amazonaws.com`, 운영 `DATABASE_URL`) | Hook | 공통 | 현재는 문자열 `prod-db` · `prod.db` 만 잡는다. `postgres://…@api-prod.cluster-xxx.rds…` 는 통과한다 |
| Hook: python ORM · 마이그레이션 `alembic upgrade|downgrade`, `manage.py migrate|flush|sqlflush`, `flask db upgrade` 가 운영 설정(`ENV=prod`, `--settings=*prod*`)일 때 차단, 그 외는 ask | Hook | 스택 코드(python) | 마이그레이션 · flush 가 운영 DB 에 적용되는 사고. CLAUDE.md §3 은 마이그레이션 추가를 사람 승인 대상으로 둔다 |
| Hook: secret-scan 패턴 보강 — YAML/JSON/dict 형태 `password: x` · `"password": "x"`, `SECRET_KEY`, `*_TOKEN`, `://user:pass@` 접속 문자열, `ghp_…` · `xox…` | Hook | 공통 | 현재 `password\s*=\s*['"]` 하나라 `.yml` · `.json` · `settings.py` 의 `SECRET_KEY = "..."` 가 아니면 `password: …` 형식은 통과한다 |
| Hook: secret-scan 트리거 보강 — `git -C dir commit`, `cd x && git commit`, `git add . && git commit`, `git commit -a` | Hook | 공통 | 트리거가 `command.startsWith("git commit")` 이다(`secret-scan.mjs:18`). 복합 명령은 아예 검사하지 않고, `-a` 는 커밋 시점에 스테이징되어 `git diff --cached` 가 비어 있다 |
| `Bash(aws s3 rm *)` · `Bash(aws s3 rb *)` · `Bash(aws s3 sync * --delete*)` · `Bash(aws * delete-*)` · `Bash(aws * terminate-*)` | ask | 공통 | 백엔드 개발에서도 클라우드 자원 삭제는 같은 의미의 파괴 명령인데 `rm -rf` 목록 밖이다 |
| `Bash(scp *)` · `Bash(rsync * *:*)` · `Bash(nc *)` · `Bash(ssh *)` | ask | 공통 | `curl` · `wget` 만 막으면 같은 외부 전송이 다른 명령으로 가능하다 |
| `WebFetch(domain:docs.python.org)` · `WebFetch(domain:docs.sqlalchemy.org)` · `WebFetch(domain:fastapi.tiangolo.com)` · `WebFetch(domain:docs.djangoproject.com)` · `WebFetch(domain:pypi.org)`(읽기) | allow | 스택 코드(python) | 현재 허용 도메인이 spring · gradle · MDN 뿐이라 python 공식 문서 조회마다 프롬프트가 뜬다. 실제 쓰는 프레임워크만 남긴다 |

## 3. 주의 (다른 스택에 그대로 걸면 일을 막을 수 있는 규칙)

1. **node 필수 Hook** — `hook-node.sh` 는 node 를 못 찾으면 **모든 Bash 호출을 차단**한다(`scripts/hook-node.sh:14-17`). node 를 쓰지 않는 python · 인프라 개발자 PC · dev container 에서는 Claude 가 Bash 를 하나도 못 쓴다. 전사 적용 전에 (a) node 설치를 온보딩 필수로 두거나 (b) Hook 을 bash · python 으로 옮겨야 한다.
2. **`curl` · `wget` 전면 deny** — 외부 API 연동 테스트, 헬스 체크(`curl localhost:8000/health`), 설치 스크립트 · 패키지 다운로드가 모두 막힌다. 외부 호스트만 deny 하고 `localhost`·`127.0.0.1` 은 allow 하는 형태가 일을 덜 막는다.
3. **`prod-db` 부분 문자열 매칭** (`settings.json:10-11`, `block-dangerous.mjs:22`) — 명령 문자열 전체를 보므로 `git commit -m "prod-db 접속 규칙 문서화"`, `grep prod-db docs/`, RCA 문서 작성 명령도 차단된다. 운영 DB 장애 문서를 쓰는 팀은 접속 명령만 잡도록 좁혀야 한다.
4. **`DROP TABLE|DATABASE` Hook** (`block-dangerous.mjs:21`) — 대소문자 무시 + 명령 전체 스캔이라 `DROP TABLE IF EXISTS tmp_…` 로 스테이징 · 테스트 테이블을 정리하는 `psql -c`, 테스트 DB 초기화 스크립트, heredoc 이 막힌다. 테스트 · 임시 테이블 정리를 하는 스택(python 통합 테스트, 배치)에서는 일을 막는다.
5. **`password = '…'` 시크릿 패턴** (`secret-scan.mjs:38`) — pytest fixture · 테스트 설정의 `password = "test1234"`, 샘플 설정의 더미값도 커밋을 막는다(CLAUDE.md 는 실습용 더미 `app-pass` 를 허용하지만 Hook 은 구분하지 못한다). 테스트 경로 제외 또는 allowlist 주석(`# secret-scan:ignore`)이 필요하다.
6. **`.env.*` 의 Edit · Read deny** (`settings.json:15, 21`) — 글롭이 `.env.example` · `.env.sample` 까지 포함해, 온보딩용 예시 파일을 만들거나 고칠 수 없다.
7. **`npm ci *` ask** (`settings.json:30`) — CLAUDE.md 는 의존성 설치에 `npm ci` 를 쓰라고 하는데 매번 프롬프트가 뜬다. web 스택에서는 ask 대신 allow 로 옮길지 정해야 한다. python 에서는 해당 없음.
8. **스택 전용 allow · ask 의 노이즈** — `npm *` · `./gradlew *` · spring/gradle/MDN 도메인 · `mcp__item-bank__*` 는 python backend 에서는 쓸 일이 없다. 전사 공통 파일에 그대로 두면 무해하지만, 공통 규칙과 스택 규칙을 파일(또는 프로필)로 분리하지 않으면 다른 스택이 자기 allow 목록을 이 파일에 계속 덧붙이게 된다.
9. **`docker compose --profile *` ask** (`settings.json:33`) — 이 저장소의 `--profile modern up -d` 기동을 매번 확인하게 한다. 로컬 compose 로 DB · 큐를 매일 띄우는 python backend 에서는 반복 프롬프트가 된다.
10. **`mcp__dbhub-mssql`** (`settings.json:37`) — 이 저장소 레거시 MSSQL 용 서버 이름이다. 다른 팀의 DB MCP 이름과 다를 수 있어 서버명 기준 규칙은 팀별로 맞춰야 한다.

## 4. 확인하지 못한 것

- Hook 을 실제 명령으로 실행해 보지는 않았다. 위 동작은 스크립트 소스를 읽고 정리한 것이다.
- `settings.json` 의 글롭 매칭 세부 동작(`Bash(git push --force *)` 가 `--force` 뒤 인자 없이 쓰인 경우 등)은 Claude Code 문서로 확인하지 않았다. 표의 "플래그 위치 무관" · "`./.env` 는 루트만" 항목은 패턴 문자열 기준 판단이다.
