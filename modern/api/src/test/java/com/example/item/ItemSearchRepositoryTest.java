package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 검색 쿼리 슬라이스 — H2(MariaDB 모드)에 실제 SQL 을 날린다.
 * 컬레이션에 따른 동작(단원 코드 대소문자, 제목 정렬)은 H2 에서 재현되지 않으므로 여기서 확인하지 않는다.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ItemSearchRepository.class)
@ActiveProfiles("test")
class ItemSearchRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemSearchRepository itemSearchRepository;

    private Unit fraction;

    @BeforeEach
    void seed() {
        fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5));
        Unit decimal = entityManager.persist(ItemFixtures.unit(3, "M5-3", "소수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag word = entityManager.persist(ItemFixtures.tag(2, "문장제"));

        entityManager.persist(ItemFixtures.item(null, fraction, "분모가 같은 분수의 덧셈", 2, ItemStatus.ACTIVE, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 문장제", 4, ItemStatus.ACTIVE, word, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "검수 중 문항", 3, ItemStatus.REVIEWING));
        entityManager.persist(ItemFixtures.item(null, fraction, "삭제된 문항", 4, ItemStatus.DELETED));
        entityManager.persist(ItemFixtures.item(null, fraction, "난이도 5 분수", 5, ItemStatus.ACTIVE, word));
        entityManager.persist(ItemFixtures.item(null, decimal, "소수 곱셈", 4, ItemStatus.ACTIVE));
        entityManager.flush();
        entityManager.clear();
    }

    private static ItemSearchCondition condition(String q, String unit, String level, String tag, String sort,
            String dir, String page) {
        return ItemSearchCondition.parse(
            new ItemSearchRequest(q, unit, level, tag, sort, dir, page), code -> true, name -> true);
    }

    private static ItemSearchCondition noCondition() {
        return condition(null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("조건 없음: 공개 문항 중 난이도 5 미만만, 난이도 내림차순 · id 오름차순")
    void excludesHiddenAndLevelFiveByDefault() {
        List<ItemSearchRow> rows = itemSearchRepository.findPage(noCondition());

        assertThat(itemSearchRepository.count(noCondition())).isEqualTo(3);
        assertThat(rows).extracting(ItemSearchRow::title)
            .containsExactly("분수 문장제", "소수 곱셈", "분모가 같은 분수의 덧셈");
    }

    @Test
    @DisplayName("행에는 단원 코드와 태그 이름(태그 id 순)이 담긴다")
    void mapsUnitCodeAndTagsInTagIdOrder() {
        ItemSearchRow row = itemSearchRepository.findPage(noCondition()).get(0);

        assertThat(row.unit()).isEqualTo("M5-1");
        assertThat(row.level()).isEqualTo(4);
        assertThat(row.tags()).containsExactly("계산", "문장제");
    }

    @Test
    @DisplayName("난이도 5 를 지정하면 난이도 5 공개 문항만 나온다")
    void findsLevelFiveWhenRequested() {
        ItemSearchCondition level5 = condition(null, null, "5", null, null, null, null);

        assertThat(itemSearchRepository.findPage(level5)).extracting(ItemSearchRow::title)
            .containsExactly("난이도 5 분수");
    }

    @Test
    @DisplayName("키워드 · 단원 · 난이도 · 태그는 AND 로 결합된다")
    void combinesConditionsWithAnd() {
        ItemSearchCondition all = condition("분수", "M5-1", "4", "문장제", null, null, null);

        assertThat(itemSearchRepository.count(all)).isEqualTo(1);
        assertThat(itemSearchRepository.findPage(all)).extracting(ItemSearchRow::title)
            .containsExactly("분수 문장제");
    }

    @Test
    @DisplayName("키워드는 지문에서도 부분 일치로 찾는다")
    void matchesKeywordInStem() {
        // ItemFixtures 는 지문을 "<제목> 문제 본문" 으로 만든다
        ItemSearchCondition stem = condition("문제 본문", null, null, null, null, null, null);

        assertThat(itemSearchRepository.count(stem)).isEqualTo(3);
    }

    @Test
    @DisplayName("태그는 이름이 정확히 같을 때만 걸린다")
    void matchesTagByExactName() {
        assertThat(itemSearchRepository.count(condition(null, null, null, "계산", null, null, null))).isEqualTo(2);
        assertThat(itemSearchRepository.count(condition(null, null, null, "계", null, null, null))).isZero();
    }

    @Test
    @DisplayName("범위 밖 난이도 · 없는 단원은 오류 없이 0건")
    void returnsZeroForOutOfRangeLevelAndUnknownUnit() {
        assertThat(itemSearchRepository.count(condition(null, null, "6", null, null, null, null))).isZero();
        assertThat(itemSearchRepository.count(condition(null, "Z9-99", null, null, null, null, null))).isZero();
    }

    @Test
    @DisplayName("단원 정렬은 방향과 관계없이 보조 정렬이 난이도 내림차순이다")
    void sortsByUnitThenLevelDescending() {
        entityManager.persist(ItemFixtures.item(null, fraction, "쉬운 분수", 1, ItemStatus.ACTIVE));
        entityManager.flush();

        ItemSearchCondition byUnitDesc = condition(null, null, null, null, "unit", "desc", null);
        List<ItemSearchRow> rows = itemSearchRepository.findPage(byUnitDesc);

        assertThat(rows).extracting(ItemSearchRow::title)
            .containsExactly("소수 곱셈", "분수 문장제", "분모가 같은 분수의 덧셈", "쉬운 분수");
    }

    @Test
    @DisplayName("한 페이지 20건, 2페이지는 21번째 행부터")
    void pagesTwentyRowsPerPage() {
        for (int i = 0; i < ItemSearchCondition.PAGE_SIZE; i++) {
            entityManager.persist(ItemFixtures.item(null, fraction, "추가 문항 " + i, 1, ItemStatus.ACTIVE));
        }
        entityManager.flush();

        ItemSearchCondition page2 = condition(null, null, null, null, null, null, "2");

        assertThat(itemSearchRepository.findPage(noCondition())).hasSize(ItemSearchCondition.PAGE_SIZE);
        assertThat(itemSearchRepository.findPage(page2)).extracting(ItemSearchRow::title)
            .containsExactly("추가 문항 17", "추가 문항 18", "추가 문항 19");
    }
}
