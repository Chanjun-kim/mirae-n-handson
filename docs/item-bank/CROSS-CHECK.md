# 레거시 비즈니스 규칙 교차 검증 (CX)

`legacy/item-bank-php` 코드만 읽고 뽑은 규칙이다. `docs/` 의 다른 문서는 읽지 않았고, 주석과 코드가 다르면 코드를 기준으로 썼다.

## 읽은 파일 · 범위

| 파일 | 용도 |
|------|------|
| `search.php` | 검색 조건 조합 · 정렬 · 페이지 |
| `register.php` | 등록 검증 |
| `units.php` | 단원 목록의 기본 정렬 · 제외 조건 |
| `index.php` · `inc/db.php` · `inc/layout.php` | 메뉴 · 공통 코드 확인용 |

- `vendor/` 는 읽지 않았다.
- **수정 화면은 찾지 못했다.** `index.php:9-11` 의 메뉴에 검색 · 등록 · 단원 목록만 있고, `edit.php` · `item.php` · `item_form.php` · `item_save.php` 는 없었다. 디렉터리 목록 명령(Bash)이 실패해 파일명을 추측해 읽었으므로, 위 이름 밖의 파일이 있는지는 확인하지 못했다. 그래서 "수정 시 검증" 규칙은 아직 없다.
- 검색은 `v_item_public` 뷰를 조회한다(`search.php:522`). 뷰 정의는 `legacy/item-bank-php` 밖(DB 스크립트)에 있어 읽지 않았다. 뷰가 어떤 문항을 걸러 내는지는 이 문서에서 **확인하지 못한 항목**이다.

## 1. 검색 — 조건 조합

### CX-01 키워드는 제목 또는 지문에 부분 일치한다
- 규칙: `q` 는 앞뒤 공백을 제거한 뒤 `title` 또는 `stem` 중 하나라도 `%키워드%` 로 LIKE 일치하면 통과한다.
- 근거: `search.php:85`, `search.php:97-98`
```php
$q = trim($q);
$like = '%' . $q . '%';
$where .= " AND (title LIKE ? OR stem LIKE ?)";
```

### CX-02 키워드는 100자까지만 쓴다
- 규칙: 100자를 넘는 키워드는 앞 100자로 자르고 경고를 남기며, 검색은 그대로 진행한다.
- 근거: `search.php:86-89`
```php
if (mb_strlen($q, 'UTF-8') > 100) {
    $q = mb_substr($q, 0, 100, 'UTF-8');
    $warnings[] = '키워드가 너무 길어 100자까지만 사용했습니다.';
```

### CX-03 키워드의 `%` · `_` 는 이스케이프하지 않는다
- 규칙: 키워드에 `%` 나 `_` 가 있으면 경고만 하고 와일드카드로 그대로 LIKE 에 넣는다.
- 근거: `search.php:94-97`
```php
if (strpos($q, '%') !== false || strpos($q, '_') !== false) {
    $warnings[] = '키워드의 % 와 _ 는 와일드카드로 처리됩니다.';
}
```

### CX-04 단원은 코드가 정확히 같아야 한다
- 규칙: `unit` 은 `unit_code = ?` 로 정확히 일치 비교한다. 대소문자 변환이나 형식 검사로 값을 바꾸거나 거절하지 않고, 형식이 틀리거나 소문자여도 경고만 남기고 입력값 그대로 조회한다.
- 근거: `search.php:111-121`
```php
if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) { // 형식이 달라도 그대로 조회한다
$where .= " AND unit_code = ?";
$values[] = $unit;
```

### CX-05 난이도를 비우면 5등급 문항이 빠진다 (주석과 코드가 다름)
- 규칙: `level` 이 비어 있으면 `level < 5` 조건이 붙어 난이도 5 문항은 결과와 건수에서 모두 제외된다. 주석은 "1~5 모두 포함"이라고 하지만 코드는 5를 뺀다. 코드를 기준으로 쓴다.
- 근거: `search.php:213-216`
```php
// 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
if ($level == '') {
    $where .= " AND level < 5";
```
- 참고: 건수 SQL 도 같은 `$where` 를 쓴다(`search.php:526`). 난이도를 `5` 로 명시하면 `level = ?` 로 조회되어 나온다(CX-06).

### CX-06 난이도 1~5 를 지정하면 그 값과 같은 문항만 나온다
- 규칙: `level` 이 `1`~`5` 한 글자이면 `level = ?` 로 정확히 일치 비교한다.
- 근거: `search.php:217-220`
```php
else if (preg_match('/^[1-5]$/', $level)) {
    $where .= " AND level = ?";
    $values[] = (int)$level;
```

### CX-07 범위 밖 난이도는 거절하지 않고 정수로 바꿔 비교한다
- 규칙: `1~5` 형식이 아닌 값은 경고를 남기고 `(int)` 로 바꾼 값과 같은 문항을 찾는다. 숫자가 아닌 값은 0이 되어 대개 0건이다.
- 근거: `search.php:223-229`
```php
$warnings[] = '난이도는 1~5 사이여야 합니다.';
$where .= " AND level = ?";
$values[] = (int)$level;
```

### CX-08 태그는 이름이 정확히 같은 태그가 붙은 문항만 나온다
- 규칙: `tag` 는 `tag.name = ?` 로 정확히 일치하는 태그가 `item_tag` 로 연결된 문항만 통과한다. 50자를 넘으면 50자로 자르고, 등록되지 않은 태그 이름이어도 경고만 남기고 조회한다(결과 0건).
- 근거: `search.php:240-247`
```php
if (mb_strlen($tag, 'UTF-8') > 50) { $tag = mb_substr($tag, 0, 50, 'UTF-8');
$where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
        . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
```

### CX-09 조건은 모두 AND 로 조합하고, 값은 하나만 받는다
- 규칙: 키워드 · 단원 · 난이도 · 태그는 `WHERE 1=1` 에 `AND` 로 이어 붙는다. 같은 이름의 파라미터가 배열로 들어오면 첫 값만 쓴다. 각 값은 `trim` 한 뒤 비어 있으면 조건으로 쓰지 않는다(난이도만 예외, CX-05).
- 근거: `search.php:44`, `search.php:55`
```php
$where    = " WHERE 1=1";
$q = is_array($params['q']) ? (string)reset($params['q']) : (string)$params['q'];
```

### CX-10 검색 대상은 `v_item_public` 뷰다
- 규칙: 목록과 건수 조회는 모두 `v_item_public` 에서만 한다. 어떤 문항이 뷰에 들어오는지는 이 폴더의 코드로 확인할 수 없다(범위 밖, 위 "읽은 파일 · 범위" 참조).
- 근거: `search.php:522`, `search.php:526`
```php
$from   = " FROM v_item_public";
$countSql = "SELECT COUNT(*) AS cnt" . $from . $where;
```

## 2. 검색 — 기본 정렬 · 페이지

### CX-11 기본 정렬은 난이도 내림차순, 같으면 번호 오름차순이다
- 규칙: `sort` 가 비어 있으면 `level DESC, id ASC` 이다. 알 수 없는 `sort` 값도 경고 후 같은 정렬을 쓴다.
- 근거: `search.php:317-319`, `search.php:322-325`
```php
case '':
    $orderBy = " ORDER BY level DESC, id ASC";
default: $warnings[] = '알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.';
```

### CX-12 정렬 기준별로 방향 기본값과 보조 정렬이 다르다
- 규칙: `id` 는 `desc` 일 때만 내림차순이고 아니면 오름차순이다. `title` 은 제목 뒤에 `id ASC` 를 붙인다. `unit` 은 `unit_code` 뒤에 `level DESC, id ASC` 를 붙인다. `level` · `created` 는 `asc` 일 때만 오름차순이고 그 밖에는 내림차순이다(`id ASC` 보조). `dir` 이 `asc`/`desc` 가 아니면 경고 후 비운 것으로 본다.
- 근거: `search.php:268-273`, `search.php:295`, `search.php:302-306`
```php
if ($dir !== 'asc' && $dir !== 'desc') {
$orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";
if ($dir === 'asc') { $orderBy = " ORDER BY level ASC, id ASC"; } else { $orderBy = " ORDER BY level DESC, id ASC"; }
```

### CX-13 한 페이지는 20건이고 페이지 번호는 1~999 로 보정한다
- 규칙: `LIMIT 20` 이고 `offset = (page-1)*20` 이다. 숫자가 아닌 값은 1페이지, 1 미만은 1, 999 초과는 999 로 바꾼다.
- 근거: `search.php:337-349`, `search.php:523`
```php
if ($page > 999) { $page = 999;
$offset = ($page - 1) * 20;
$limit  = " LIMIT 20 OFFSET " . (int)$offset;
```

## 3. 등록 검증 (`register.php`)

### CX-14 제목은 앞뒤 공백을 제거한 뒤 5자 이상이다
- 규칙: 제목은 `trim` 한 길이가 5 미만이면 오류다. 중간 공백은 글자 수에 포함한다.
- 근거: `register.php:41`, `register.php:49-51`
```php
$title  = isset($_POST['title'])  ? trim((string)$_POST['title'])  : '';
if (mb_strlen($title, 'UTF-8') < 5) {
    $errors[] = '제목은 5자 이상 입력해야 합니다.';
```

### CX-15 제목은 200자를 넘을 수 없다
- 규칙: `trim` 한 제목이 200자를 넘으면 오류다(잘라 주지 않고 거절한다).
- 근거: `register.php:52-54`
```php
if (mb_strlen($title, 'UTF-8') > 200) {
    $errors[] = '제목은 200자를 넘을 수 없습니다.';
```

### CX-16 지문은 필수이고 길이 제한이 없다
- 규칙: `trim` 한 지문이 빈 문자열이면 오류다. 최대 길이 검사는 코드에 없다.
- 근거: `register.php:42`, `register.php:56-58`
```php
if ($stem === '') {
    $errors[] = '지문을 입력해야 합니다.';
```

### CX-17 단원은 존재하는 단원 id 여야 한다
- 규칙: `unit_id` 가 `unit` 테이블에서 읽어 온 목록의 `id` 중 하나와 문자열로 같아야 한다. 아니면 오류다.
- 근거: `register.php:60-68`
```php
if ((string)$u['id'] === $unitId) { $unitOk = true; }
if (!$unitOk) { $errors[] = '단원을 선택해야 합니다.';
```

### CX-18 난이도는 1~5 한 글자 숫자만 허용한다
- 규칙: `level` 이 `^[1-5]$` 가 아니면 오류다(빈 값 포함). 검색(CX-07)과 달리 범위 밖 값을 거절한다.
- 근거: `register.php:70-72`
```php
if (!preg_match('/^[1-5]$/', $level)) {
    $errors[] = '난이도는 1~5 사이여야 합니다.';
```

### CX-19 태그는 목록에 있는 것만 저장하고, 없는 태그는 오류 없이 버린다
- 규칙: 선택한 태그 id 중 `tag` 목록에 있는 것만 저장 대상이 된다. 목록에 없는 id 는 오류를 내지 않고 조용히 제외한다. 태그는 필수가 아니다.
- 근거: `register.php:74-81`
```php
if ((string)$t['id'] === (string)$tid) {
    $validTagIds[] = (int)$tid;
```

### CX-20 등록한 문항은 검수중(R) 상태로 저장된다
- 규칙: 등록 직후 `status` 는 항상 `'R'` 이다. 등록 화면의 안내 문구는 검수가 끝나야 검색에 나온다고 하지만, 이 코드 안에서 확인되는 것은 `'R'` 저장까지다. 검색에서 빠지는지는 뷰 정의(CX-10)에 달려 있어 확인하지 못했다.
- 근거: `register.php:92-94`, `register.php:116`
```php
"INSERT INTO item (id, unit_id, title, stem, level, status, created_at, updated_at)
 VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"
```

## 4. 단원 목록 (`units.php`)

### CX-21 단원 목록의 문항 수는 `status = 'A'` 만 센다
- 규칙: 단원별 문항 수에는 `status = 'A'` 인 문항만 포함한다. 그 밖의 상태(예: 등록 직후 `R`)는 세지 않는다. 문항이 0개인 단원도 목록에 나온다.
- 근거: `units.php:15-17`
```php
// 단원별 공개(status='A') 문항 수. 삭제 · 검수중 문항은 세지 않는다.
(SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
```

### CX-22 단원 목록은 학년 오름차순, 같으면 코드 오름차순이다
- 규칙: 단원 목록의 정렬은 `grade ASC, code ASC` 이다. 검색 화면의 단원 선택 목록(`search.php:156`)과 등록 화면(`register.php:27`)도 같은 순서다.
- 근거: `units.php:19`
```php
ORDER BY u.grade ASC, u.code ASC
```

## 5. 주석과 코드가 다른 곳

| 위치 | 주석 | 코드 | 이 문서의 기준 |
|------|------|------|----------------|
| `search.php:213-216` | 난이도가 비면 "1~5 모두 포함" | `level < 5` 로 5 제외 | 코드 (CX-05) |
