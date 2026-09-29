package com.example.item;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * {@code GET /api/items/search} 응답.
 *
 * @param status   HTTP 상태 코드(항상 200 — 잘못된 입력도 레거시처럼 0건으로 응답한다)
 * @param rows     현재 페이지의 행. 응답 순서가 정렬 순서다
 * @param count    조건에 맞는 전체 건수(현재 페이지 행 수가 아니다)
 * @param message  전체 건수가 0이면 {@link #NO_RESULT_MESSAGE}, 아니면 {@code null}
 * @param warnings 입력값 경고. 레거시 화면의 경고 목록과 같은 문구 · 순서
 */
public record ItemSearchResponse(
    int status,
    List<ItemSearchRow> rows,
    long count,
    String message,
    List<String> warnings) {

    /** 레거시 {@code search.php:658} 의 안내 문구. */
    static final String NO_RESULT_MESSAGE = "검색 결과가 없습니다";

    static ItemSearchResponse of(long count, List<ItemSearchRow> rows, List<String> warnings) {
        return new ItemSearchResponse(
            HttpStatus.OK.value(), rows, count, count == 0 ? NO_RESULT_MESSAGE : null, warnings);
    }
}
