package com.example.item;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문항 검색 API — 레거시 {@code search.php} 를 옮긴 것. 파라미터 이름도 레거시와 같다
 * ({@code q}, {@code unit}, {@code level}, {@code tag}, {@code sort}, {@code dir}, {@code page}).
 */
@RestController
@RequestMapping("/api/items")
public class ItemSearchController {

    private final ItemSearchService itemSearchService;

    public ItemSearchController(ItemSearchService itemSearchService) {
        this.itemSearchService = itemSearchService;
    }

    /**
     * {@code GET /api/items/search} — 공개 문항 검색.
     * 반복 · 배열({@code level[]}) 파라미터를 레거시처럼 다루려고 원본 파라미터 맵으로 받는다.
     */
    @GetMapping("/search")
    public ItemSearchResponse search(@RequestParam MultiValueMap<String, String> params) {
        return itemSearchService.search(ItemSearchRequest.from(params));
    }
}
