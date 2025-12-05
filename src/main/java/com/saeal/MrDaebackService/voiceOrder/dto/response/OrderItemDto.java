package com.saeal.MrDaebackService.voiceOrder.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemDto {
    private String dinnerId;
    private String dinnerName;
    private String servingStyleId;
    private String servingStyleName;
    private int quantity;
    private int basePrice;      // dinner 기본 가격
    private int unitPrice;      // dinner + style 가격
    private int totalPrice;     // unitPrice * quantity

    // ★ GUI와 동일하게: Product 정보 추가
    private String productId;   // 생성된 Product ID

    // ★ 커스터마이징용: Product의 메뉴 아이템 목록
    @Builder.Default
    private List<MenuItemCustomization> menuItems = new ArrayList<>();

    // 메뉴 아이템 커스터마이징 정보
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MenuItemCustomization {
        private String menuItemId;
        private String menuItemName;
        private int defaultQuantity;    // 기본 수량
        private int currentQuantity;    // 현재 수량 (사용자가 변경)
        private int unitPrice;          // 단가

        // 가격 차이 계산 (현재 - 기본)
        public int getPriceDiff() {
            return (currentQuantity - defaultQuantity) * unitPrice;
        }
    }
}
