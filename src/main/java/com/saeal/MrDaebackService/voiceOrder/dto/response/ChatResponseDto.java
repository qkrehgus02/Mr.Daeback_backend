package com.saeal.MrDaebackService.voiceOrder.dto.response;

import com.saeal.MrDaebackService.voiceOrder.enums.OrderFlowState;
import com.saeal.MrDaebackService.voiceOrder.enums.UiAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponseDto {
    private String userMessage;              // 사용자 메시지 (STT 변환 결과 포함)
    private String assistantMessage;         // AI 응답 메시지
    private OrderFlowState flowState;        // 현재 플로우 상태
    private UiAction uiAction;               // 프론트 UI 액션 (백엔드에서 결정)
    private List<OrderItemDto> currentOrder; // 현재 장바구니 (요약 정보)
    private BigDecimal totalPrice;           // 총 가격
    private String selectedAddress;          // 선택된 배달 주소
    private List<String> userAddresses;      // 사용자 저장 주소 목록
    private StoreUpdateDto storeUpdate;      // 프론트엔드 Store 업데이트 정보 (핵심!)

    // ★ 추가 메뉴 (별도 Product로 생성되는 메뉴들)
    @Builder.Default
    private List<AdditionalMenuItemDto> additionalMenuItems = new ArrayList<>();

    // ★ 특별 요청사항
    private String specialRequest;

    // 주문 완료 시 정보
    private String orderId;                  // 생성된 주문 ID
    private String orderNumber;              // 주문 번호

    // 추가 메뉴 아이템 DTO
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdditionalMenuItemDto {
        private String menuItemId;
        private String menuItemName;
        private int quantity;
        private int unitPrice;
        private int totalPrice;  // unitPrice * quantity
    }
}
