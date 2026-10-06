package com.example.item;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 문항 검색 서비스 — 레거시 {@code search.php} 의 동작을 그대로 옮긴다.
 *
 * <p>잘못된 입력(없는 단원, 범위 밖 난이도 등)은 오류가 아니라 경고와 함께 0건 이상의 결과로 응답한다.
 * 레거시처럼 건수 조회와 행 조회를 각각의 문장으로 실행하며, 트랜잭션으로 묶지 않는다.
 */
@Service
public class ItemSearchService {

    private static final Logger log = LoggerFactory.getLogger(ItemSearchService.class);

    private final ItemSearchRepository itemSearchRepository;
    private final UnitRepository unitRepository;
    private final TagRepository tagRepository;

    public ItemSearchService(ItemSearchRepository itemSearchRepository, UnitRepository unitRepository,
            TagRepository tagRepository) {
        this.itemSearchRepository = itemSearchRepository;
        this.unitRepository = unitRepository;
        this.tagRepository = tagRepository;
    }

    /** 공개 문항 검색. 한 페이지 20건, 전체 건수 · 안내 문구 · 입력값 경고를 함께 돌려준다. */
    public ItemSearchResponse search(ItemSearchRequest request) {
        ItemSearchCondition condition = ItemSearchCondition.parse(request, this::unitExists, this::tagKnown);

        long count = itemSearchRepository.count(condition);
        List<ItemSearchRow> rows = count == 0 ? List.of() : itemSearchRepository.findPage(condition);
        log.debug("item search: total={}, rows={}, page={}, warnings={}",
            count, rows.size(), condition.page(), condition.warnings().size());
        return ItemSearchResponse.of(count, rows, condition.warnings());
    }

    /** 레거시 :125 — {@code SELECT ... FROM unit WHERE code = ?}. 비교는 DB 컬레이션을 따른다. */
    private boolean unitExists(String code) {
        return unitRepository.findByCode(code).isPresent();
    }

    /**
     * 레거시 :251-256 — 등록된 태그 이름과 {@code ===}(대소문자 구분)로 비교한다.
     * DB 조회는 컬레이션(utf8mb4_unicode_ci)에 따라 대소문자를 구분하지 않으므로, 찾은 이름을 다시 비교한다.
     */
    private boolean tagKnown(String name) {
        return tagRepository.findByName(name).map(Tag::getName).filter(name::equals).isPresent();
    }
}
