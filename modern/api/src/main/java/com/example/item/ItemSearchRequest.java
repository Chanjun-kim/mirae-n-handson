package com.example.item;

import java.util.List;
import org.springframework.util.MultiValueMap;

/**
 * 문항 검색 요청 — 레거시 {@code search.php} 와 같은 이름의 쿼리 파라미터를 가공하지 않은 문자열로 담는다.
 * 파라미터가 없으면 {@code null}. 해석(trim · 자르기 · 범위 확인)은 {@link ItemSearchCondition} 이 한다.
 *
 * <p>PHP 의 쿼리 파싱을 따른다(레거시 {@code search.php:55-79}).
 * {@code 이름[]} 로 들어오면 배열의 첫 값을, 같은 이름이 반복되면 마지막 값을 쓴다.
 */
public record ItemSearchRequest(
    String q,
    String unit,
    String level,
    String tag,
    String sort,
    String dir,
    String page) {

    private static final String ARRAY_SUFFIX = "[]";

    static ItemSearchRequest from(MultiValueMap<String, String> params) {
        return new ItemSearchRequest(
            pick(params, "q"),
            pick(params, "unit"),
            pick(params, "level"),
            pick(params, "tag"),
            pick(params, "sort"),
            pick(params, "dir"),
            pick(params, "page"));
    }

    private static String pick(MultiValueMap<String, String> params, String name) {
        List<String> array = params.get(name + ARRAY_SUFFIX);
        if (array != null && !array.isEmpty()) {
            return array.get(0);
        }
        List<String> plain = params.get(name);
        if (plain != null && !plain.isEmpty()) {
            return plain.get(plain.size() - 1);
        }
        return null;
    }
}
