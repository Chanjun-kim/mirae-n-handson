package com.example.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 해석이 끝난 문항 검색 조건. 레거시 {@code search.php} 의 {@code buildSearchQuery}(:42-564) 가
 * 파라미터를 다루는 방식을 그대로 옮긴다 — 잘못된 값도 거부하지 않고, 경고만 남긴 채 조회한다.
 *
 * @param keyword    제목 · 지문 부분 일치 키워드. 빈 문자열이면 조건 없음
 * @param unit       단원 코드. 빈 문자열이면 조건 없음
 * @param level      난이도. {@code null} 이면 {@code level < MAX_LEVEL}
 * @param tag        태그 이름. 빈 문자열이면 조건 없음
 * @param sort       정렬 기준
 * @param dir        정렬 방향. {@code asc} · {@code desc} · 빈 문자열
 * @param page       페이지 번호(1~{@link #MAX_PAGE})
 * @param warnings   입력값 경고. 레거시가 화면에 보여 주던 문구 그대로, 같은 순서
 */
record ItemSearchCondition(
    String keyword,
    String unit,
    Long level,
    String tag,
    ItemSort sort,
    String dir,
    int page,
    List<String> warnings) {

    static final int KEYWORD_MAX_LENGTH = 100;
    static final int TAG_MAX_LENGTH = 50;
    static final int MAX_LEVEL = 5;
    static final int PAGE_SIZE = 20;
    static final int MAX_PAGE = 999;

    /** PHP {@code trim()} 이 지우는 문자: 공백, \t, \n, \r, \0, \x0B. */
    private static final String PHP_TRIM_CHARS = " \t\n\r\0\u000B";
    /** PHP {@code is_numeric_string} 이 앞에서 건너뛰는 공백. */
    private static final String PHP_NUMERIC_LEADING_CHARS = " \t\n\r\u000B\f";
    private static final String DEFAULT_PAGE = "1";

    // PCRE 의 $ 는 끝의 \n 하나 앞에서도 맞는다. Java 에서는 UNIX_LINES 로 같은 동작을 얻는다.
    private static final Pattern UNIT_FORMAT =
        Pattern.compile("^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$", Pattern.UNIX_LINES);
    private static final Pattern LEVEL_FORMAT = Pattern.compile("^[1-5]$", Pattern.UNIX_LINES);
    private static final Pattern PAGE_FORMAT = Pattern.compile("^[0-9]+$", Pattern.UNIX_LINES);
    private static final Pattern PHP_NUMERIC_PREFIX = Pattern.compile(
        "^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?");

    static final String WARN_KEYWORD_TOO_LONG = "키워드가 너무 길어 100자까지만 사용했습니다.";
    static final String WARN_KEYWORD_ONE_CHAR = "키워드가 한 글자라 결과가 많을 수 있습니다.";
    static final String WARN_KEYWORD_WILDCARD = "키워드의 % 와 _ 는 와일드카드로 처리됩니다.";
    static final String WARN_UNIT_FORMAT = "단원 코드 형식이 올바르지 않습니다. (예: M5-1)";
    static final String WARN_UNIT_LOWERCASE = "단원 코드는 대문자로 입력하세요. (입력값 그대로 조회합니다)";
    static final String WARN_UNIT_UNKNOWN = "등록되지 않은 단원 코드입니다: ";
    static final String WARN_LEVEL_RANGE = "난이도는 1~5 사이여야 합니다.";
    static final String WARN_TAG_WILDCARD = "태그는 부분 일치를 지원하지 않습니다.";
    static final String WARN_TAG_TOO_LONG = "태그 이름이 너무 길어 50자까지만 사용했습니다.";
    static final String WARN_TAG_UNKNOWN = "등록되지 않은 태그입니다: ";
    static final String WARN_DIR_INVALID = "정렬 방향은 asc 또는 desc 만 가능합니다.";
    static final String WARN_SORT_UNKNOWN = "알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.";
    static final String WARN_PAGE_INVALID = "페이지 번호가 올바르지 않아 1페이지를 표시합니다.";
    static final String WARN_PAGE_TOO_LARGE = "페이지 번호는 999 를 넘을 수 없습니다.";

    /**
     * 요청 → 조건. 경고 순서는 레거시와 같다(키워드 → 단원 → 난이도 → 태그 → 정렬 → 페이지).
     *
     * @param unitExists 단원 코드가 {@code unit} 테이블에 있는지(DB 컬레이션으로 비교)
     * @param tagKnown   태그 이름이 등록 목록에 있는지(대소문자 구분, 레거시 {@code :253} 의 {@code ===})
     */
    static ItemSearchCondition parse(ItemSearchRequest request, Predicate<String> unitExists,
            Predicate<String> tagKnown) {
        List<String> warnings = new ArrayList<>();

        // 키워드 — :85-98
        String keyword = phpTrim(request.q());
        if (codePointLength(keyword) > KEYWORD_MAX_LENGTH) {
            keyword = truncate(keyword, KEYWORD_MAX_LENGTH);
            warnings.add(WARN_KEYWORD_TOO_LONG);
        }
        if (!keyword.isEmpty()) {
            if (codePointLength(keyword) == 1) {
                warnings.add(WARN_KEYWORD_ONE_CHAR);
            }
            if (hasWildcard(keyword)) {
                warnings.add(WARN_KEYWORD_WILDCARD);
            }
        }

        // 단원 — :109-147. 형식 · 대소문자 · 등록 여부가 틀려도 입력값 그대로 조회한다
        String unit = phpTrim(request.unit());
        if (!unit.isEmpty()) {
            if (!UNIT_FORMAT.matcher(unit).find()) {
                warnings.add(WARN_UNIT_FORMAT);
            }
            if (!unit.equals(asciiUpper(unit))) {
                warnings.add(WARN_UNIT_LOWERCASE);
            }
            if (!unitExists.test(unit)) {
                warnings.add(WARN_UNIT_UNKNOWN + unit);
            }
        }

        // 난이도 — :211-230. 비어 있으면 level < 5(난이도 5 제외). 주석("1~5 모두 포함")과 다르지만 코드대로 옮긴다
        String levelRaw = phpTrim(request.level());
        Long level;
        if (levelRaw.isEmpty()) {
            level = null;
        } else if (LEVEL_FORMAT.matcher(levelRaw).find()) {
            level = phpIntval(levelRaw);
        } else {
            warnings.add(WARN_LEVEL_RANGE);
            level = phpIntval(levelRaw);
        }

        // 태그 — :235-261
        String tag = phpTrim(request.tag());
        if (!tag.isEmpty()) {
            if (hasWildcard(tag)) {
                warnings.add(WARN_TAG_WILDCARD);
            }
            if (codePointLength(tag) > TAG_MAX_LENGTH) {
                tag = truncate(tag, TAG_MAX_LENGTH);
                warnings.add(WARN_TAG_TOO_LONG);
            }
            if (!tagKnown.test(tag)) {
                warnings.add(WARN_TAG_UNKNOWN + tag);
            }
        }

        // 정렬 — :266-326
        String sortKey = asciiLower(phpTrim(request.sort()));
        String dir = asciiLower(phpTrim(request.dir()));
        if (!"asc".equals(dir) && !"desc".equals(dir)) {
            if (!dir.isEmpty()) {
                warnings.add(WARN_DIR_INVALID);
            }
            dir = "";
        }
        ItemSort sort = ItemSort.fromKey(sortKey);
        if (sort == null) {
            warnings.add(WARN_SORT_UNKNOWN);
            sort = ItemSort.DEFAULT;
        }

        // 페이지 — :336-348. page 는 trim 하지 않는다
        String pageRaw = request.page() == null ? DEFAULT_PAGE : request.page();
        long page = 1;
        if (PAGE_FORMAT.matcher(pageRaw).find()) {
            page = phpIntval(pageRaw);
        } else if (!pageRaw.isEmpty() && !DEFAULT_PAGE.equals(pageRaw)) {
            warnings.add(WARN_PAGE_INVALID);
        }
        if (page < 1) {
            page = 1;
        }
        if (page > MAX_PAGE) {
            page = MAX_PAGE;
            warnings.add(WARN_PAGE_TOO_LARGE);
        }

        return new ItemSearchCondition(keyword, unit, level, tag, sort, dir, (int) page, List.copyOf(warnings));
    }

    /** 건너뛸 행 수 — (page - 1) × 20. */
    int offset() {
        return (page - 1) * PAGE_SIZE;
    }

    /** 제목 · 지문 LIKE 패턴. % · _ 를 이스케이프하지 않는다(레거시 :97). */
    String likePattern() {
        return "%" + keyword + "%";
    }

    String orderBy() {
        return sort.orderBy(dir);
    }

    /** PHP {@code trim()}. {@code null} 은 빈 문자열(파라미터 없음과 같다). */
    static String phpTrim(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && PHP_TRIM_CHARS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && PHP_TRIM_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * PHP 7.4 의 {@code (int)$string}. 앞 공백을 건너뛰고 숫자로 읽을 수 있는 앞부분만 쓴다.
     * 숫자가 없으면 0, 소수 · 지수는 double 로 읽어 0 쪽으로 버린다({@code 2.7 → 2}, {@code 1e3 → 1000}).
     * long 범위를 넘으면 최댓값 · 최솟값으로 붙이고, 무한대는 0 이다.
     */
    static long phpIntval(String value) {
        int start = 0;
        while (start < value.length() && PHP_NUMERIC_LEADING_CHARS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        Matcher m = PHP_NUMERIC_PREFIX.matcher(value.substring(start));
        if (!m.find()) {
            return 0;
        }
        String number = m.group();
        boolean integral = !number.contains(".") && m.group(1) == null;
        if (integral) {
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException overflow) {
                return capToLong(Double.parseDouble(number));
            }
        }
        return capToLong(Double.parseDouble(number));
    }

    private static long capToLong(double d) {
        if (Double.isInfinite(d) || Double.isNaN(d)) {
            return 0;
        }
        return (long) d;
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    /** {@code mb_substr($value, 0, max, 'UTF-8')} — 글자(코드 포인트) 단위로 자른다. */
    private static String truncate(String value, int max) {
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    private static boolean hasWildcard(String value) {
        return value.indexOf('%') >= 0 || value.indexOf('_') >= 0;
    }

    /** PHP {@code strtolower} (C 로캘) — ASCII 만 바꾼다. */
    private static String asciiLower(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return sb.toString();
    }

    /** PHP {@code strtoupper} (C 로캘) — ASCII 만 바꾼다. */
    private static String asciiUpper(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c);
        }
        return sb.toString();
    }
}
