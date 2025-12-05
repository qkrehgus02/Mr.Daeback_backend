package com.saeal.MrDaebackService.voiceOrder.dto.response;

import com.saeal.MrDaebackService.voiceOrder.enums.OrderFlowState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 프론트엔드 Store 업데이트 정보
 * 백엔드가 수행한 작업을 프론트엔드 Store에 반영하기 위한 정보
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreUpdateDto {
    // 현재 플로우 상태
    private OrderFlowState flowState;

    // 주소 업데이트
    private String address;  // null이면 업데이트 안 함

    // 디너 추가
    private List<DinnerAddDto> dinnersToAdd;  // 추가할 디너 목록

    // 스타일 설정 및 Product 생성 (각 인스턴스별)
    private List<StyleSetDto> stylesToSet;  // 설정할 스타일 목록 (Product 정보 포함)

    // 메뉴 아이템 수량 변경
    private List<MenuItemUpdateDto> menuItemsToUpdate;  // 업데이트할 메뉴 아이템 목록

    // 공통 추가 메뉴 추가
    private List<AdditionalMenuItemAddDto> additionalMenuItemsToAdd;  // 추가할 공통 메뉴 목록

    // 특별 요청사항 (메모)
    private String memo;  // null이면 업데이트 안 함

    // 디너 추가 정보
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DinnerAddDto {
        private String dinnerId;
        private String dinnerName;
        private String description;
        private BigDecimal basePrice;
        private Integer quantity;  // 추가할 수량
    }

    // 스타일 설정 및 Product 생성 정보
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StyleSetDto {
        private String dinnerId;         // Dinner ID
        private String dinnerName;       // Dinner 이름
        private Integer instanceIndex;   // 인스턴스 인덱스
        private String styleId;          // ServingStyle ID
        private String styleName;        // ServingStyle 이름
        private BigDecimal styleExtraPrice;  // 스타일 추가 가격

        // 생성된 Product 정보 (핵심!)
        private String productId;        // 생성된 Product ID
        private String productName;      // Product 이름
        private BigDecimal totalPrice;   // Product 총 가격
        private List<ProductMenuItemDto> productMenuItems;  // 기본 메뉴 아이템 목록
    }

    // Product의 메뉴 아이템 정보
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductMenuItemDto {
        private String menuItemId;
        private String menuItemName;
        private Integer defaultQuantity;
        private Integer currentQuantity;
        private BigDecimal unitPrice;
    }

    // 메뉴 아이템 업데이트 정보
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MenuItemUpdateDto {
        private String dinnerId;         // Dinner ID (매칭용)
        private Integer instanceIndex;   // 인스턴스 인덱스
        private String productId;        // Product ID
        private String menuItemId;       // 변경할 메뉴 아이템 ID
        private String menuItemName;     // 메뉴 아이템 이름
        private Integer quantity;        // 변경할 수량
    }

    // 공통 추가 메뉴 추가 정보
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdditionalMenuItemAddDto {
        private String menuItemId;
        private String menuItemName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private String productId;  // 생성된 ADDITIONAL_MENU_PRODUCT ID
    }
}

