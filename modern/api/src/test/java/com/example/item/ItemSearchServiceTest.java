package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

/** 검색 서비스 — 파라미터 해석 규칙(레거시 search.php)과 응답 조립을 확인한다. */
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ItemSearchServiceTest {

    @Mock
    private ItemSearchRepository itemSearchRepository;

    @Mock
    private UnitRepository unitRepository;

    @Mock
    private TagRepository tagRepository;

    @InjectMocks
    private ItemSearchService itemSearchService;

    private static ItemSearchRequest request(String q, String unit, String level, String tag, String sort,
            String dir, String page) {
        return new ItemSearchRequest(q, unit, level, tag, sort, dir, page);
    }

    /** 0건으로 응답하게 두고, 저장소에 넘어간 조건을 꺼낸다. */
    private ItemSearchCondition searchAndCapture(ItemSearchRequest request) {
        when(itemSearchRepository.count(any())).thenReturn(0L);
        itemSearchService.search(request);
        ArgumentCaptor<ItemSearchCondition> captor = ArgumentCaptor.forClass(ItemSearchCondition.class);
        verify(itemSearchRepository).count(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("건수가 있으면 행을 조회하고 message 는 null")
    void returnsRowsAndNullMessageWhenFound() {
        List<ItemSearchRow> rows = List.of(new ItemSearchRow(6, "분수 문항", "M5-1", 2, List.of("계산")));
        when(itemSearchRepository.count(any())).thenReturn(1L);
        when(itemSearchRepository.findPage(any())).thenReturn(rows);

        ItemSearchResponse response = itemSearchService.search(request(null, null, null, null, null, null, null));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.count()).isEqualTo(1);
        assertThat(response.rows()).isEqualTo(rows);
        assertThat(response.message()).isNull();
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    @DisplayName("전체 건수가 0이면 안내 문구를 싣고 행 조회는 하지 않는다")
    void returnsNoResultMessageWhenCountIsZero() {
        when(itemSearchRepository.count(any())).thenReturn(0L);

        ItemSearchResponse response = itemSearchService.search(request(null, null, "6", null, null, null, null));

        assertThat(response.count()).isZero();
        assertThat(response.rows()).isEmpty();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
        verify(itemSearchRepository, never()).findPage(any());
    }

    @Test
    @DisplayName("건수가 있는데 페이지가 비면 message 는 null (건수 기준)")
    void keepsNullMessageWhenPageIsEmptyButCountIsNot() {
        when(itemSearchRepository.count(any())).thenReturn(20L);
        when(itemSearchRepository.findPage(any())).thenReturn(List.of());

        ItemSearchResponse response = itemSearchService.search(request(null, null, null, null, null, null, "2"));

        assertThat(response.count()).isEqualTo(20);
        assertThat(response.rows()).isEmpty();
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("값은 PHP trim 문자(공백 · 탭 · 줄바꿈 등)만 지우고, 전각 공백은 남긴다")
    void trimsOnlyPhpTrimCharacters() {
        ItemSearchCondition condition = searchAndCapture(request(" \t분수\n", null, " ", null, null, null, null));

        assertThat(condition.keyword()).isEqualTo("분수");
        assertThat(condition.level()).isNull();
        assertThat(ItemSearchCondition.phpTrim("　분수　")).isEqualTo("　분수　");
    }

    @Test
    @DisplayName("키워드 100자는 자르지 않고 경고도 없다")
    void keepsKeywordOf100Characters() {
        ItemSearchCondition exact = searchAndCapture(request("가".repeat(100), null, null, null, null, null, null));
        assertThat(exact.keyword()).hasSize(100);
        assertThat(exact.warnings()).isEmpty();
    }

    @Test
    @DisplayName("키워드 101자는 100자로 자르고 경고, 글자 수는 코드 포인트로 센다")
    void truncatesKeywordByCodePoints() {
        String emoji = "😀".repeat(101);
        ItemSearchCondition condition = searchAndCapture(request(emoji, null, null, null, null, null, null));

        assertThat(condition.keyword()).isEqualTo("😀".repeat(100));
        assertThat(condition.warnings()).containsExactly(ItemSearchCondition.WARN_KEYWORD_TOO_LONG);
    }

    @Test
    @DisplayName("한 글자 · 와일드카드 키워드는 경고만 하고 LIKE 패턴은 그대로")
    void warnsOnOneCharAndWildcardKeyword() {
        ItemSearchCondition condition = searchAndCapture(request("%", null, null, null, null, null, null));

        assertThat(condition.likePattern()).isEqualTo("%%%");
        assertThat(condition.warnings()).containsExactly(
            ItemSearchCondition.WARN_KEYWORD_ONE_CHAR, ItemSearchCondition.WARN_KEYWORD_WILDCARD);
    }

    @Test
    @DisplayName("소문자 단원 코드는 경고하고 입력값 그대로 조회한다")
    void keepsLowercaseUnitWithWarning() {
        when(unitRepository.findByCode("m5-1"))
            .thenReturn(Optional.of(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5)));

        ItemSearchCondition condition = searchAndCapture(request(null, "m5-1", null, null, null, null, null));

        assertThat(condition.unit()).isEqualTo("m5-1");
        assertThat(condition.warnings()).containsExactly(ItemSearchCondition.WARN_UNIT_LOWERCASE);
    }

    @Test
    @DisplayName("없는 단원 코드는 404 가 아니라 경고 후 그대로 조회")
    void warnsOnUnknownUnit() {
        when(unitRepository.findByCode("Z9-99")).thenReturn(Optional.empty());

        ItemSearchCondition condition = searchAndCapture(request(null, "Z9-99", null, null, null, null, null));

        assertThat(condition.unit()).isEqualTo("Z9-99");
        assertThat(condition.warnings()).containsExactly(ItemSearchCondition.WARN_UNIT_UNKNOWN + "Z9-99");
    }

    @Test
    @DisplayName("난이도 1~5 는 그 값, 빈 값은 난이도 5 제외(null)")
    void parsesLevelInRangeAndEmpty() {
        assertThat(ItemSearchCondition.parse(request(null, null, "5", null, null, null, null), c -> true, t -> true)
            .level()).isEqualTo(5L);
        assertThat(ItemSearchCondition.parse(request(null, null, "", null, null, null, null), c -> true, t -> true)
            .level()).isNull();
        assertThat(searchAndCapture(request(null, null, null, null, null, null, null)).level()).isNull();
    }

    @Test
    @DisplayName("범위 밖 난이도는 경고하고 PHP (int) 변환값으로 조회한다")
    void convertsOutOfRangeLevelLikePhpIntCast() {
        ItemSearchCondition condition = searchAndCapture(request(null, null, "2.7", null, null, null, null));

        assertThat(condition.level()).isEqualTo(2L);
        assertThat(condition.warnings()).containsExactly(ItemSearchCondition.WARN_LEVEL_RANGE);
    }

    @Test
    @DisplayName("PHP (int) 변환: 숫자 없음 0, 앞부분 숫자만, 지수, 넘치는 값은 최댓값")
    void emulatesPhpIntCast() {
        assertThat(ItemSearchCondition.phpIntval("abc")).isZero();
        assertThat(ItemSearchCondition.phpIntval("6")).isEqualTo(6L);
        assertThat(ItemSearchCondition.phpIntval("12abc")).isEqualTo(12L);
        assertThat(ItemSearchCondition.phpIntval("+3")).isEqualTo(3L);
        assertThat(ItemSearchCondition.phpIntval("-1")).isEqualTo(-1L);
        assertThat(ItemSearchCondition.phpIntval("1e3")).isEqualTo(1000L);
        assertThat(ItemSearchCondition.phpIntval("99999999999999999999")).isEqualTo(Long.MAX_VALUE);
        assertThat(ItemSearchCondition.phpIntval("1e999")).isZero();
    }

    @Test
    @DisplayName("태그 51자는 50자로 자르고, 등록 여부는 대소문자를 구분해 비교한다")
    void truncatesTagAndChecksRegistration() {
        when(tagRepository.findByName("가".repeat(50))).thenReturn(Optional.empty());

        ItemSearchCondition condition = searchAndCapture(request(null, null, null, "가".repeat(51), null, null, null));

        assertThat(condition.tag()).isEqualTo("가".repeat(50));
        assertThat(condition.warnings()).containsExactly(
            ItemSearchCondition.WARN_TAG_TOO_LONG, ItemSearchCondition.WARN_TAG_UNKNOWN + "가".repeat(50));
    }

    @Test
    @DisplayName("등록된 태그와 이름이 정확히 같으면 경고가 없다")
    void acceptsRegisteredTag() {
        when(tagRepository.findByName("계산")).thenReturn(Optional.of(ItemFixtures.tag(1, "계산")));

        ItemSearchCondition condition = searchAndCapture(request(null, null, null, "계산", null, null, null));

        assertThat(condition.warnings()).isEmpty();
    }

    @Test
    @DisplayName("DB 가 대소문자만 다른 태그를 돌려줘도 등록되지 않은 태그로 경고한다")
    void warnsWhenTagDiffersOnlyInCase() {
        when(tagRepository.findByName("calc")).thenReturn(Optional.of(ItemFixtures.tag(1, "Calc")));

        ItemSearchCondition condition = searchAndCapture(request(null, null, null, "calc", null, null, null));

        assertThat(condition.warnings()).containsExactly(ItemSearchCondition.WARN_TAG_UNKNOWN + "calc");
    }

    @Test
    @DisplayName("정렬: 대소문자 · 공백 무시, 잘못된 방향은 무시, 모르는 기준은 기본 정렬")
    void resolvesSortAndDirection() {
        ItemSearchCondition title = searchAndCapture(request(null, null, null, null, " TITLE ", "Desc", null));
        assertThat(title.orderBy()).isEqualTo("i.title DESC, i.id ASC");

        ItemSearchCondition unknown = ItemSearchCondition.parse(
            request(null, null, null, null, "price", "up", null), c -> true, t -> true);
        assertThat(unknown.orderBy()).isEqualTo("i.level DESC, i.id ASC");
        assertThat(unknown.warnings()).containsExactly(
            ItemSearchCondition.WARN_DIR_INVALID, ItemSearchCondition.WARN_SORT_UNKNOWN);
    }

    @Test
    @DisplayName("페이지: 음수 · 숫자 아님은 1(경고), 0 은 1(경고 없음), 999 초과는 999(경고)")
    void clampsPage() {
        assertThat(pageOf("-1")).isEqualTo(1);
        assertThat(warningsOfPage("-1")).containsExactly(ItemSearchCondition.WARN_PAGE_INVALID);
        assertThat(pageOf("0")).isEqualTo(1);
        assertThat(warningsOfPage("0")).isEmpty();
        assertThat(warningsOfPage("")).isEmpty();
        assertThat(pageOf("999")).isEqualTo(999);
        assertThat(pageOf("1000")).isEqualTo(999);
        assertThat(warningsOfPage("1000")).containsExactly(ItemSearchCondition.WARN_PAGE_TOO_LARGE);
        assertThat(pageOf("99999999999999999999")).isEqualTo(999);
        assertThat(pageOf(" 2")).isEqualTo(1);
        assertThat(searchAndCapture(request(null, null, null, null, null, null, "2")).offset()).isEqualTo(20);
    }

    private static int pageOf(String page) {
        return ItemSearchCondition.parse(request(null, null, null, null, null, null, page), c -> true, t -> true)
            .page();
    }

    private static List<String> warningsOfPage(String page) {
        return ItemSearchCondition.parse(request(null, null, null, null, null, null, page), c -> true, t -> true)
            .warnings();
    }
}
