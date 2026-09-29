// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// 케이스 설계 근거: docs/item-bank/BUSINESS-RULES.md 의 규칙 ID(BR-xx).
// 케이스 이름에 겨냥한 규칙 ID 를 적는다. 이름이 스냅샷의 키이므로 바꾸면 스냅샷이 새로 찍힌다.
//
// 규칙
// - 대상 주소는 lib/target.mjs 가 정한다(환경 변수 TARGET_BASE_URL, 없으면 레거시 기본 주소).
// - 응답은 fetchNormalized 로 {status, rows, count, message} 모양으로 바꾼 뒤 스냅샷과 비교한다.
// - 기대값을 손으로 적지 않는다. 지금 시스템의 실제 응답이 기대값이다(npm run baseline -- item-bank).
// - 레거시와 새 API 양쪽에서 같은 결과가 나와야 하므로 건너뛰기를 두지 않는다.
//
// 시드 데이터 참고(db/mariadb/init/02-seed.sql): status='A' AND level<5 인 문항이 정확히 20건(= 한 페이지).
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const PATH = '/search.php';

/** 한글 한 글자를 n 번 이어 붙인다. 서버는 mb_strlen(글자 수)으로 재므로 바이트 수와 글자 수가 다른 값을 쓴다. */
const hangul = (n) => '가'.repeat(n);

const search = (params) => fetchNormalized(MODULE, PATH, params);

describe('item-bank · 문항 검색(search.php)', () => {
  describe('정상 입력', () => {
    it('키워드만 — 공개 문항 · 부분 일치 · 난이도 5 제외 · 기본 정렬 (BR-01, BR-06, BR-11, BR-17)', async () => {
      expect(await search({ q: '분수' })).toMatchSnapshot();
    });

    it('단원 + 난이도 AND 결합 (BR-04, BR-09, BR-12)', async () => {
      expect(await search({ unit: 'M5-1', level: '2' })).toMatchSnapshot();
    });

    it('태그 정확 일치 + 제목 오름차순 정렬 (BR-14, BR-18, BR-32)', async () => {
      expect(await search({ tag: '계산', sort: 'title', dir: 'asc' })).toMatchSnapshot();
    });
  });

  describe('규칙의 경계값', () => {
    it('난이도 상한 5 를 명시 (BR-12, BR-11)', async () => {
      expect(await search({ level: '5' })).toMatchSnapshot();
    });

    it('난이도 범위 바로 밖 6 (BR-13)', async () => {
      expect(await search({ level: '6' })).toMatchSnapshot();
    });

    it('키워드 100자 — 자르기 상한 그대로 (BR-07)', async () => {
      expect(await search({ q: hangul(100) })).toMatchSnapshot();
    });

    it('키워드 101자 — 자르기 상한 + 1 (BR-07)', async () => {
      expect(await search({ q: hangul(101) })).toMatchSnapshot();
    });

    it('태그 51자 — 자르기 상한 + 1, 없는 태그 (BR-15, BR-16)', async () => {
      expect(await search({ tag: hangul(51) })).toMatchSnapshot();
    });

    it('페이지 999 — 상한 그대로, 전체 페이지 수 초과 (BR-21, BR-20)', async () => {
      expect(await search({ page: '999' })).toMatchSnapshot();
    });

    it('페이지 1000 — 상한 + 1, 999 로 보정 (BR-21, BR-20)', async () => {
      expect(await search({ page: '1000' })).toMatchSnapshot();
    });

    it('기본 조건의 2페이지 — 결과가 정확히 20건일 때 다음 페이지 (BR-20, BR-11, BR-22)', async () => {
      expect(await search({ page: '2' })).toMatchSnapshot();
    });

    it('한 글자 키워드 — 경고 후 그대로 검색 (BR-08)', async () => {
      expect(await search({ q: '수' })).toMatchSnapshot();
    });
  });

  describe('빈 값 · 누락', () => {
    it('파라미터 전부 없음 — 첫 화면 기본 동작 (BR-04, BR-11, BR-17, BR-20)', async () => {
      expect(await search({})).toMatchSnapshot();
    });

    it('파라미터 전부 빈 문자열 (BR-04, BR-11, BR-17, BR-21)', async () => {
      expect(
        await search({ q: '', unit: '', level: '', tag: '', sort: '', dir: '', page: '' }),
      ).toMatchSnapshot();
    });

    it('공백만 — trim 후 빈 값이면 조건 없음 (BR-04, BR-06, BR-11)', async () => {
      expect(await search({ q: '   ', unit: ' ', level: ' ', tag: ' ' })).toMatchSnapshot();
    });
  });

  describe('이상한 값', () => {
    it('음수 페이지 -1 (BR-21)', async () => {
      expect(await search({ page: '-1' })).toMatchSnapshot();
    });

    // 1000자(UTF-8 인코딩 9,000바이트)는 웹 서버 요청 줄 길이 제한(Apache 기본 8,190바이트)에
    // 먼저 걸려 PHP 규칙이 아닌 서버 설정을 재게 된다. 그래서 상한의 5배인 500자를 쓴다.
    it('아주 긴 키워드 500자 (BR-07, BR-06)', async () => {
      expect(await search({ q: hangul(500) })).toMatchSnapshot();
    });

    it('형식은 맞지만 없는 단원 코드 Z9-99 (BR-10, BR-09)', async () => {
      expect(await search({ unit: 'Z9-99' })).toMatchSnapshot();
    });

    it('소문자 단원 코드 m5-1 — 대소문자 구분 여부 확정 (BR-10, BR-09)', async () => {
      expect(await search({ unit: 'm5-1' })).toMatchSnapshot();
    });

    it('숫자가 아닌 난이도 abc — (int) 변환 0 (BR-13)', async () => {
      expect(await search({ level: 'abc' })).toMatchSnapshot();
    });

    it('소수 난이도 2.7 — (int) 변환 2 (BR-13)', async () => {
      expect(await search({ level: '2.7' })).toMatchSnapshot();
    });

    it('와일드카드 키워드 % (BR-08, BR-06)', async () => {
      expect(await search({ q: '%' })).toMatchSnapshot();
    });

    it('배열 파라미터 level[]=5&level[]=1 — 첫 값만 사용 (BR-05)', async () => {
      expect(await search({ 'level[]': ['5', '1'] })).toMatchSnapshot();
    });
  });
});
