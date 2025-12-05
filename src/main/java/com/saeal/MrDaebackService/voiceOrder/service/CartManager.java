package com.saeal.MrDaebackService.voiceOrder.service;

import com.saeal.MrDaebackService.dinner.dto.response.DinnerResponseDto;
import com.saeal.MrDaebackService.product.domain.Product;
import com.saeal.MrDaebackService.product.dto.response.ProductResponseDto;
import com.saeal.MrDaebackService.product.repository.ProductRepository;
import com.saeal.MrDaebackService.servingStyle.dto.response.ServingStyleResponseDto;
import com.saeal.MrDaebackService.voiceOrder.dto.request.ChatRequestDto.OrderItemRequestDto;
import com.saeal.MrDaebackService.voiceOrder.dto.response.ChatResponseDto.AdditionalMenuItemDto;
import com.saeal.MrDaebackService.voiceOrder.dto.response.OrderItemDto;
import com.saeal.MrDaebackService.voiceOrder.dto.response.OrderItemDto.MenuItemCustomization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 임시 장바구니 관리
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CartManager {

    private final MenuMatcher menuMatcher;
    private final ProductRepository productRepository;

    /**
     * 기존 장바구니를 OrderItemDto 리스트로 변환
     * ★ menuItems가 비어있고 productId가 있으면 DB에서 다시 로드
     */
    public List<OrderItemDto> convertToOrderItemDtoList(List<OrderItemRequestDto> currentOrder) {
        List<OrderItemDto> result = new ArrayList<>();
        if (currentOrder == null) return result;

        for (OrderItemRequestDto item : currentOrder) {
            List<MenuItemCustomization> menuItems = new ArrayList<>();

            // 1. 프론트에서 보낸 menuItems가 있으면 사용
            if (item.getMenuItems() != null && !item.getMenuItems().isEmpty()) {
                for (var mi : item.getMenuItems()) {
                    menuItems.add(MenuItemCustomization.builder()
                            .menuItemId(mi.getMenuItemId())
                            .menuItemName(mi.getMenuItemName())
                            .defaultQuantity(mi.getDefaultQuantity())
                            .currentQuantity(mi.getCurrentQuantity())
                            .unitPrice(mi.getUnitPrice())
                            .build());
                }
            }
            // 2. ★ menuItems가 비어있고 productId가 있으면 DB에서 로드
            else if (item.getProductId() != null && !item.getProductId().isEmpty()) {
                try {
                    Product product = productRepository.findById(UUID.fromString(item.getProductId()))
                            .orElse(null);
                    if (product != null && product.getProductMenuItems() != null) {
                        for (var pmi : product.getProductMenuItems()) {
                            if (pmi.getMenuItem() != null) {
                                menuItems.add(MenuItemCustomization.builder()
                                        .menuItemId(pmi.getMenuItem().getId().toString())
                                        .menuItemName(pmi.getMenuItem().getName())
                                        .defaultQuantity(pmi.getQuantity())
                                        .currentQuantity(pmi.getQuantity())
                                        .unitPrice(pmi.getUnitPrice() != null ? pmi.getUnitPrice().intValue() : 0)
                                        .build());
                            }
                        }
                    }
                } catch (Exception e) {
                    // 메뉴 아이템 로드 실패 시 빈 리스트로 진행
                }
            }

            OrderItemDto dto = OrderItemDto.builder()
                    .dinnerId(item.getDinnerId())
                    .dinnerName(item.getDinnerName())
                    .servingStyleId(item.getServingStyleId())
                    .servingStyleName(item.getServingStyleName())
                    .quantity(item.getQuantity())
                    .basePrice(item.getBasePrice())
                    .unitPrice(item.getUnitPrice())
                    .totalPrice(item.getTotalPrice())
                    .productId(item.getProductId())
                    .menuItems(menuItems)
                    .build();

            result.add(dto);
        }
        return result;
    }

    /**
     * 메뉴 추가
     */
    public OrderItemDto addMenu(DinnerResponseDto dinner, int quantity) {
        int basePrice = dinner.getBasePrice().intValue();
        return OrderItemDto.builder()
                .dinnerId(dinner.getId().toString())
                .dinnerName(dinner.getDinnerName())
                .quantity(quantity)
                .basePrice(basePrice)
                .unitPrice(basePrice)
                .totalPrice(basePrice * quantity)
                .menuItems(new ArrayList<>())
                .build();
    }

    /**
     * 메뉴 추가 (수량 없이 - 임시 아이템)
     */
    public OrderItemDto addMenuWithoutQuantity(DinnerResponseDto dinner) {
        int basePrice = dinner.getBasePrice().intValue();
        return OrderItemDto.builder()
                .dinnerId(dinner.getId().toString())
                .dinnerName(dinner.getDinnerName())
                .quantity(0)
                .basePrice(basePrice)
                .unitPrice(basePrice)
                .totalPrice(0)
                .menuItems(new ArrayList<>())
                .build();
    }

    /**
     * 수량 설정
     */
    public OrderItemDto setQuantity(OrderItemDto item, int quantity) {
        return OrderItemDto.builder()
                .dinnerId(item.getDinnerId())
                .dinnerName(item.getDinnerName())
                .servingStyleId(item.getServingStyleId())
                .servingStyleName(item.getServingStyleName())
                .quantity(quantity)
                .basePrice(item.getBasePrice())
                .unitPrice(item.getUnitPrice())
                .totalPrice(item.getUnitPrice() * quantity)
                .productId(item.getProductId())
                .menuItems(item.getMenuItems() != null ? new ArrayList<>(item.getMenuItems()) : new ArrayList<>())
                .build();
    }

    /**
     * 아이템에 스타일 적용 (처음 적용)
     */
    public OrderItemDto applyStyleToItem(OrderItemDto item, ServingStyleResponseDto style) {
        int newUnitPrice = item.getBasePrice() + style.getExtraPrice().intValue();

        return OrderItemDto.builder()
                .dinnerId(item.getDinnerId())
                .dinnerName(item.getDinnerName())
                .servingStyleId(style.getId().toString())
                .servingStyleName(style.getStyleName())
                .quantity(item.getQuantity())
                .basePrice(item.getBasePrice())
                .unitPrice(newUnitPrice)
                .totalPrice(newUnitPrice * item.getQuantity())
                .productId(item.getProductId())
                .menuItems(item.getMenuItems() != null ? new ArrayList<>(item.getMenuItems()) : new ArrayList<>())
                .build();
    }

    /**
     * 스타일 변경 (기존 스타일 → 새 스타일)
     */
    public OrderItemDto changeStyle(OrderItemDto item, ServingStyleResponseDto newStyle) {
        int newUnitPrice = item.getBasePrice() + newStyle.getExtraPrice().intValue();

        return OrderItemDto.builder()
                .dinnerId(item.getDinnerId())
                .dinnerName(item.getDinnerName())
                .servingStyleId(newStyle.getId().toString())
                .servingStyleName(newStyle.getStyleName())
                .quantity(item.getQuantity())
                .basePrice(item.getBasePrice())
                .unitPrice(newUnitPrice)
                .totalPrice(newUnitPrice * item.getQuantity())
                .productId(item.getProductId())
                .menuItems(item.getMenuItems() != null ? new ArrayList<>(item.getMenuItems()) : new ArrayList<>())
                .build();
    }

    /**
     * ★ Product 정보 설정 (스타일 선택 후 Product 생성 시)
     */
    public OrderItemDto setProductInfo(OrderItemDto item, ProductResponseDto product) {
        List<MenuItemCustomization> menuItems = new ArrayList<>();

        // Product의 메뉴 아이템을 커스터마이징 목록으로 변환
        if (product.getProductMenuItems() != null) {
            for (var pmi : product.getProductMenuItems()) {
                menuItems.add(MenuItemCustomization.builder()
                        .menuItemId(pmi.getMenuItemId())
                        .menuItemName(pmi.getMenuItemName())
                        .defaultQuantity(pmi.getQuantity())
                        .currentQuantity(pmi.getQuantity())
                        .unitPrice(pmi.getUnitPrice() != null ? pmi.getUnitPrice().intValue() : 0)
                        .build());
            }
        }

        return OrderItemDto.builder()
                .dinnerId(item.getDinnerId())
                .dinnerName(item.getDinnerName())
                .servingStyleId(item.getServingStyleId())
                .servingStyleName(item.getServingStyleName())
                .quantity(item.getQuantity())
                .basePrice(item.getBasePrice())
                .unitPrice(item.getUnitPrice())
                .totalPrice(product.getTotalPrice() != null ? product.getTotalPrice().intValue() : item.getTotalPrice())
                .productId(product.getId())
                .menuItems(menuItems)
                .build();
    }

    /**
     * ★ 메뉴 아이템 수량 변경
     */
    public OrderItemDto updateMenuItemQuantity(OrderItemDto item, String menuItemId, int newQuantity) {
        List<MenuItemCustomization> updatedMenuItems = new ArrayList<>();

        for (MenuItemCustomization mi : item.getMenuItems()) {
            if (mi.getMenuItemId().equals(menuItemId)) {
                updatedMenuItems.add(MenuItemCustomization.builder()
                        .menuItemId(mi.getMenuItemId())
                        .menuItemName(mi.getMenuItemName())
                        .defaultQuantity(mi.getDefaultQuantity())
                        .currentQuantity(Math.max(0, newQuantity))
                        .unitPrice(mi.getUnitPrice())
                        .build());
            } else {
                updatedMenuItems.add(mi);
            }
        }

        // 가격 재계산
        int menuItemPriceDiff = updatedMenuItems.stream()
                .mapToInt(MenuItemCustomization::getPriceDiff)
                .sum();
        int newTotalPrice = (item.getUnitPrice() + menuItemPriceDiff) * item.getQuantity();

        return OrderItemDto.builder()
                .dinnerId(item.getDinnerId())
                .dinnerName(item.getDinnerName())
                .servingStyleId(item.getServingStyleId())
                .servingStyleName(item.getServingStyleName())
                .quantity(item.getQuantity())
                .basePrice(item.getBasePrice())
                .unitPrice(item.getUnitPrice())
                .totalPrice(newTotalPrice)
                .productId(item.getProductId())
                .menuItems(updatedMenuItems)
                .build();
    }

    /**
     * ★ 메뉴 아이템 이름으로 찾기 (한글/영문 양방향 매칭)
     */
    public MenuItemCustomization findMenuItemByName(OrderItemDto item, String menuItemName) {
        if (item.getMenuItems() == null || menuItemName == null) return null;

        // MenuMatcher의 한글-영문 매핑을 사용해서 검색
        return item.getMenuItems().stream()
                .filter(mi -> menuMatcher.isMatchingMenuItem(mi.getMenuItemName(), menuItemName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 총 가격 계산 (디너 + 메뉴 아이템 커스터마이징)
     */
    public int calculateTotalPrice(List<OrderItemDto> orderItems) {
        return orderItems.stream()
                .mapToInt(OrderItemDto::getTotalPrice)
                .sum();
    }

    /**
     * 추가 메뉴 총 가격 계산
     */
    public int calculateAdditionalMenuTotalPrice(List<AdditionalMenuItemDto> additionalMenuItems) {
        if (additionalMenuItems == null) return 0;
        return additionalMenuItems.stream()
                .mapToInt(AdditionalMenuItemDto::getTotalPrice)
                .sum();
    }

    /**
     * 전체 총 가격 계산 (디너 + 추가 메뉴)
     */
    public int calculateGrandTotalPrice(List<OrderItemDto> orderItems, List<AdditionalMenuItemDto> additionalMenuItems) {
        return calculateTotalPrice(orderItems) + calculateAdditionalMenuTotalPrice(additionalMenuItems);
    }
}
