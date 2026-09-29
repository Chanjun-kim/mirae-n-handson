package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 검색 컨트롤러 슬라이스 — 파라미터 전달 방식과 응답 JSON 모양을 확인한다. */
@WebMvcTest(ItemSearchController.class)
@ActiveProfiles("test")
class ItemSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ItemSearchService itemSearchService;

    private ItemSearchRequest captureRequest() {
        ArgumentCaptor<ItemSearchRequest> captor = ArgumentCaptor.forClass(ItemSearchRequest.class);
        verify(itemSearchService).search(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("GET /api/items/search → 200, {status, rows, count, message, warnings} 모양")
    void returnsNormalizedShape() throws Exception {
        when(itemSearchService.search(any())).thenReturn(ItemSearchResponse.of(
            1, List.of(new ItemSearchRow(6, "분수 문항", "M5-1", 2, List.of("계산", "문장제"))), List.of()));

        mockMvc.perform(get("/api/items/search").param("unit", "M5-1").param("level", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.message").value(nullValue()))
            .andExpect(jsonPath("$.warnings.length()").value(0))
            .andExpect(jsonPath("$.rows[0].id").value(6))
            .andExpect(jsonPath("$.rows[0].title").value("분수 문항"))
            .andExpect(jsonPath("$.rows[0].unit").value("M5-1"))
            .andExpect(jsonPath("$.rows[0].level").value(2))
            .andExpect(jsonPath("$.rows[0].tags[1]").value("문장제"))
            .andExpect(jsonPath("$.rows[0].stem").doesNotExist())
            .andExpect(jsonPath("$.rows[0].status").doesNotExist());

        assertThat(captureRequest()).isEqualTo(new ItemSearchRequest(null, "M5-1", "2", null, null, null, null));
    }

    @Test
    @DisplayName("0건이면 404 가 아니라 200 과 안내 문구")
    void returnsOkWithMessageWhenNothingFound() throws Exception {
        when(itemSearchService.search(any())).thenReturn(ItemSearchResponse.of(
            0, List.of(), List.of(ItemSearchCondition.WARN_UNIT_UNKNOWN + "Z9-99")));

        mockMvc.perform(get("/api/items/search").param("unit", "Z9-99"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.rows.length()").value(0))
            .andExpect(jsonPath("$.message").value("검색 결과가 없습니다"))
            .andExpect(jsonPath("$.warnings[0]").value("등록되지 않은 단원 코드입니다: Z9-99"));
    }

    @Test
    @DisplayName("level[] 배열 파라미터는 첫 값을 쓴다")
    void usesFirstValueOfArrayParameter() throws Exception {
        when(itemSearchService.search(any())).thenReturn(ItemSearchResponse.of(0, List.of(), List.of()));

        mockMvc.perform(get("/api/items/search").param("level[]", "5", "1"))
            .andExpect(status().isOk());

        assertThat(captureRequest().level()).isEqualTo("5");
    }

    @Test
    @DisplayName("같은 이름이 반복되면 마지막 값을 쓴다 (쉼표로 합치지 않는다)")
    void usesLastValueOfRepeatedParameter() throws Exception {
        when(itemSearchService.search(any())).thenReturn(ItemSearchResponse.of(0, List.of(), List.of()));

        mockMvc.perform(get("/api/items/search").param("q", "분수", "소수"))
            .andExpect(status().isOk());

        assertThat(captureRequest().q()).isEqualTo("소수");
    }

    @Test
    @DisplayName("빈 값 파라미터는 빈 문자열로, 없는 파라미터는 null 로 넘긴다")
    void passesEmptyAndMissingParameters() throws Exception {
        when(itemSearchService.search(any())).thenReturn(ItemSearchResponse.of(0, List.of(), List.of()));

        mockMvc.perform(get("/api/items/search").param("level", "").param("page", ""))
            .andExpect(status().isOk());

        assertThat(captureRequest()).isEqualTo(new ItemSearchRequest(null, null, "", null, null, null, ""));
    }
}
