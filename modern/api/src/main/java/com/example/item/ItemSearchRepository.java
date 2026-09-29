package com.example.item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 문항 검색 쿼리. 조건 조합에 따라 WHERE · ORDER BY 가 바뀌므로 JPQL {@code @Query} 대신 JDBC 로 만든다.
 *
 * <p>레거시는 뷰 {@code v_item_public} 을 읽지만, 테스트용 H2 에는 뷰가 없으므로 같은 조건
 * ({@code status = 'A'}, 01-schema.sql:70)을 기본 테이블에 직접 건다.
 * 비교 · 정렬은 모두 SQL 에서 한다 — 단원 코드 대소문자, LIKE 와일드카드, 제목 정렬 순서는 DB 컬레이션을 따른다.
 */
@Repository
public class ItemSearchRepository {

    private static final String FROM = " FROM item i JOIN unit u ON u.id = i.unit_id";

    private final NamedParameterJdbcTemplate jdbc;

    public ItemSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 조건에 맞는 공개 문항 수. */
    public long count(ItemSearchCondition condition) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT COUNT(*)" + FROM + where(condition, params);
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    /** 현재 페이지의 행(최대 20건)과 각 행의 태그. */
    public List<ItemSearchRow> findPage(ItemSearchCondition condition) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT i.id, i.title, u.code AS unit_code, i.level" + FROM + where(condition, params)
            + " ORDER BY " + condition.orderBy()
            + " LIMIT :limit OFFSET :offset";
        params.addValue("limit", ItemSearchCondition.PAGE_SIZE);
        params.addValue("offset", condition.offset());

        Map<Integer, ItemSearchRow> rows = new LinkedHashMap<>();
        jdbc.query(sql, params, rs -> {
            int id = rs.getInt("id");
            rows.put(id, new ItemSearchRow(
                id, rs.getString("title"), rs.getString("unit_code"), rs.getInt("level"), new ArrayList<>()));
        });
        if (rows.isEmpty()) {
            return List.of();
        }

        // 태그 이름은 태그 id 순(레거시 뷰의 GROUP_CONCAT ... ORDER BY t.id, 01-schema.sql:64)
        String tagSql = "SELECT it.item_id, t.name FROM item_tag it JOIN tag t ON t.id = it.tag_id"
            + " WHERE it.item_id IN (:ids) ORDER BY it.item_id, t.id";
        jdbc.query(tagSql, new MapSqlParameterSource("ids", rows.keySet()),
            rs -> {
                rows.get(rs.getInt("item_id")).tags().add(rs.getString("name"));
            });
        return rows.values().stream()
            .map(r -> new ItemSearchRow(r.id(), r.title(), r.unit(), r.level(), List.copyOf(r.tags())))
            .toList();
    }

    /** 레거시 search.php 의 WHERE 조립과 같은 순서 · 같은 조건. 모든 값은 바인딩 파라미터로 넘긴다. */
    private static String where(ItemSearchCondition condition, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(" WHERE i.status = :status");
        params.addValue("status", ItemStatus.ACTIVE);

        if (!condition.keyword().isEmpty()) {
            where.append(" AND (i.title LIKE :like OR i.stem LIKE :like)");
            params.addValue("like", condition.likePattern());
        }
        if (!condition.unit().isEmpty()) {
            where.append(" AND u.code = :unit");
            params.addValue("unit", condition.unit());
        }
        if (condition.level() == null) {
            where.append(" AND i.level < :maxLevel");
            params.addValue("maxLevel", ItemSearchCondition.MAX_LEVEL);
        } else {
            where.append(" AND i.level = :level");
            params.addValue("level", condition.level());
        }
        if (!condition.tag().isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id")
                .append(" WHERE it.item_id = i.id AND t.name = :tag)");
            params.addValue("tag", condition.tag());
        }
        return where.toString();
    }
}
