# 배치 점검 리포트

점검 기간 2026-09-08 ~ 2026-09-17(10일), 이상 6건(4개 날짜, 4개 job).

- 대상: `pipeline-samples/batch-logs/*.log`, `pipeline-samples/datamart-counts.csv`
- 날짜는 로그의 `date=`(처리 대상일) 기준이다. 실제 실행은 다음 날 새벽이다.
- 점검 기준: ① 실행 여부 ② 중복 실행 ③ 적재 건수(직전 7일 평균 ±30% 초과, 0건) ④ 지연(해당 run 제외 job 평균의 2배 초과)
- 평균 · 비율은 awk 로 계산했다. 로그에 없는 원인은 적지 않고 "다음 확인 사항"으로 남겼다.

## 이상 목록

| 날짜 | job | 점검 항목 | 근거 | 영향 추정 | 다음 확인 사항 |
|---|---|---|---|---|---|
| 09-09 | build_item_stats | ① 실행 여부 (종료 기록 없음) | `batch-logs/2026-09-09.log:2` START → `:4` RUNNING 25% 이후 SUCCESS/FAIL 없음. `datamart-counts.csv` 에 09-09 build_item_stats 행 없음(09-09 행은 `:7`~`:10` 의 다른 job 만 있음) | 09-09 일자 문항 통계(`dm.item_stats_daily`)가 적재되지 않았을 가능성. 같은 날 후속 job 은 정상 시작(`:5`) | `pid=40655` 종료 여부, 10.20.7.31 호스트 시스템 로그 · OOM, `itembank.item_response` 소스 연결 상태 |
| 09-12 | export_grades | ④ 지연 | `batch-logs/2026-09-12.log:16` `elapsed=963s`. 해당 run 제외 평균 317s 의 3.04배. CSV `loaded_at` 01:16:04(다른 날은 01:05 전후) | 성적 내보내기 완료가 약 11분 늦음. 건수는 정상 범위(편차 1.5% 이내) | 해당 run 의 step 로그(`:11`~`:15`), 대상 시스템 응답 시간 |
| 09-15 | extract_submissions | ④ 지연 | `batch-logs/2026-09-15.log:20` `elapsed=1668s`. 해당 run 제외 평균 668s 의 2.50배. `:19` WARN `replica lag 412s` | 종료가 01:57:50 으로 늦어져 02:00 시작하는 load_datamart 와 간격이 거의 없었음. 건수는 정상(41,573) | grade-db(10.20.2.41) replica 지연의 시작 · 종료 시각, 지연이 원인인지 별도 확인 |
| 09-15 | load_datamart | ④ 지연 | `batch-logs/2026-09-15.log:40` `elapsed=4357s`. 해당 run 제외 평균 908s 의 4.80배. `:27` WARN `elapsed 40m exceeds p95 17m`, `:28` SLA 02:45 `NOT_FINISHED` | SLA(02:45) 위반. 이후 수동 재실행 요청(`:30`)의 계기가 됨 | 재실행 이전부터 느렸던 이유: 입력 `extract_submissions/2026-09-15` 크기, DB 락 · I/O 상태 |
| 09-15 | load_datamart | ② 중복 실행 | 같은 날 SUCCESS 2건: `batch-logs/2026-09-15.log:40`(run `ldm-20260916-0200-edf5`), `:42`(run `ldm-20260916-0258-8963`). 두 번째는 `:32` `trigger=manual lock=bypassed`, `:30` `force=true`, `:31` 락 보유 중이나 `--force` 로 무시 | 두 run 이 같은 대상(`dm.daily_submission_fact`, `mode=append`, `:21`)에 동시 적재. 락 해제도 두 번째 run 은 건너뜀(`:43`) | 적재 후 대상 테이블의 실제 행 수 · 중복 행 여부, 중복분 정리 방법 |
| 09-15 | load_datamart | ③ 적재 건수 | `datamart-counts.csv:39` `row_count=369064`. 직전 7일 평균 184,617.4 대비 +99.9%. 로그 SUCCESS 는 두 run 모두 rows=184532(`:40`, `:42`) | 데이터마트 건수가 약 2배. 하류 build_report_cache 의 `input_rows=369064`(`batch-logs/2026-09-15.log:46`)와 dq-check WARN `deviation=+99%`(`:47`)까지 전파됨 | 위 중복 실행 건과 함께 확인. 369064 가 두 번 적재의 합인지 테이블 직접 조회 |
| 09-17 | build_item_stats | ③ 적재 건수 | `datamart-counts.csv:46` `row_count=7284`. 직전 7일 평균 12,453.1 대비 -41.5%. `batch-logs/2026-09-17.log:8` `rows=7284` 로 로그와 CSV 일치, step 은 모두 정상 완료(`:3`~`:8`) | 09-17 문항 통계가 평소의 약 58% 만 적재됨. 파이프라인 오류 기록은 없음 | 원천 `itembank.item_response` 의 09-17 일자 건수, `window=1d` 범위의 데이터 적재 정상 여부 |

## 참고 (이상 판정 기준에는 해당하지 않음)

- 매일 `STU-1013` 1건이 `class_id is NULL` 로 skip 된다(예: `batch-logs/2026-09-08.log:22`).
- build_report_cache 는 `elapsed` 필드가 없어 START~SUCCESS 시각으로 계산했고(179~247s) 이상이 없었다. 건수 편차도 최대 2.7% 였다.
- 7일 평균은 직전 최대 7일(데이터가 있는 날) 기준이다. 09-09 build_item_stats 행이 없어 이 job 의 09-10 이후 평균은 일부 구간에서 6일치다.

## 이상 없음으로 판정한 날짜

5개 job 전부 실행 · 종료 · 건수 · 소요 시간 기준을 충족한 날짜다.

- 2026-09-08
- 2026-09-10
- 2026-09-11
- 2026-09-13
- 2026-09-14
- 2026-09-16
