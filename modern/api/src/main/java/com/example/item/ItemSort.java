package com.example.item;

/**
 * 문항 검색 정렬 기준. 레거시 {@code search.php:276-326} 의 ORDER BY 를 그대로 옮긴다.
 * 방향이 없거나 잘못됐으면 기준별 기본 방향을 쓴다. SQL 에는 이 고정 문자열만 들어간다.
 */
enum ItemSort {

    ID("i.id ASC", "desc", "i.id DESC"),
    TITLE("i.title ASC, i.id ASC", "desc", "i.title DESC, i.id ASC"),
    /** 보조 정렬은 방향과 관계없이 난이도 내림차순이다(레거시 {@code :295}, {@code :297}). */
    UNIT("u.code ASC, i.level DESC, i.id ASC", "desc", "u.code DESC, i.level DESC, i.id ASC"),
    LEVEL("i.level DESC, i.id ASC", "asc", "i.level ASC, i.id ASC"),
    CREATED("i.created_at DESC, i.id ASC", "asc", "i.created_at ASC, i.id ASC"),
    /** 기준이 비었거나 알 수 없는 값일 때: 어려운 문항부터, 같은 난이도면 번호 순. */
    DEFAULT("i.level DESC, i.id ASC", "", "i.level DESC, i.id ASC");

    private final String defaultOrder;
    private final String alternateDir;
    private final String alternateOrder;

    ItemSort(String defaultOrder, String alternateDir, String alternateOrder) {
        this.defaultOrder = defaultOrder;
        this.alternateDir = alternateDir;
        this.alternateOrder = alternateOrder;
    }

    /** 소문자 · trim 된 {@code sort} 값 → 기준. 알 수 없는 값이면 {@code null}. */
    static ItemSort fromKey(String key) {
        return switch (key) {
            case "id" -> ID;
            case "title" -> TITLE;
            case "unit" -> UNIT;
            case "level" -> LEVEL;
            case "created" -> CREATED;
            case "" -> DEFAULT;
            default -> null;
        };
    }

    /** {@code dir} 는 {@code asc} · {@code desc} · 빈 문자열 중 하나. */
    String orderBy(String dir) {
        return !alternateDir.isEmpty() && alternateDir.equals(dir) ? alternateOrder : defaultOrder;
    }
}
