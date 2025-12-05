package com.saeal.MrDaebackService.voiceOrder.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saeal.MrDaebackService.cart.domain.Cart;
import com.saeal.MrDaebackService.cart.enums.CartStatus;
import com.saeal.MrDaebackService.cart.repository.CartRepository;
import com.saeal.MrDaebackService.dinner.dto.response.DinnerResponseDto;
import com.saeal.MrDaebackService.order.domain.Order;
import com.saeal.MrDaebackService.order.dto.response.OrderResponseDto;
import com.saeal.MrDaebackService.order.service.OrderService;
import com.saeal.MrDaebackService.product.domain.Product;
import com.saeal.MrDaebackService.product.dto.request.CreateProductRequest;
import com.saeal.MrDaebackService.product.dto.request.UpdateProductMenuItemRequest;
import com.saeal.MrDaebackService.product.dto.response.ProductResponseDto;
import com.saeal.MrDaebackService.product.repository.ProductRepository;
import com.saeal.MrDaebackService.product.service.ProductService;
import com.saeal.MrDaebackService.servingStyle.dto.response.ServingStyleResponseDto;
import com.saeal.MrDaebackService.user.domain.User;
import com.saeal.MrDaebackService.user.repository.UserRepository;
import com.saeal.MrDaebackService.voiceOrder.dto.LlmResponseDto;
import com.saeal.MrDaebackService.voiceOrder.dto.request.ChatRequestDto;
import com.saeal.MrDaebackService.voiceOrder.dto.request.ChatRequestDto.ChatMessageDto;
import com.saeal.MrDaebackService.voiceOrder.dto.request.ChatRequestDto.OrderItemRequestDto;
import com.saeal.MrDaebackService.voiceOrder.dto.response.ChatResponseDto;
import com.saeal.MrDaebackService.voiceOrder.dto.response.OrderItemDto;
import com.saeal.MrDaebackService.voiceOrder.dto.response.StoreUpdateDto;
import com.saeal.MrDaebackService.voiceOrder.enums.OrderFlowState;
import com.saeal.MrDaebackService.voiceOrder.enums.UiAction;
import com.saeal.MrDaebackService.voiceOrder.enums.UserIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.saeal.MrDaebackService.cart.enums.DeliveryMethod;

import java.math.BigDecimal;
import java.util.*;

/**
 * 음성/텍스트 주문 처리 서비스
 *
 * 주요 흐름:
 * 1. 주소 선택 → 2. 디너 선택 → 3. 스타일 선택 → 4. 커스터마이징(선택) → 5. 결제
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceOrderService {

    private final GroqService groqService;
    private final MenuMatcher menuMatcher;
    private final CartManager cartManager;
    private final UserRepository userRepository;
    private final ProductService productService;
    private final ProductRepository productRepository;
    private final CartRepository cartRepository;
    private final OrderService orderService;
    private final ObjectMapper objectMapper;
    private final com.saeal.MrDaebackService.menuItems.repository.MenuItemsRepository menuItemsRepository;

    // ========================================
    // 메인 진입점
    // ========================================

    @Transactional
    public ChatResponseDto processChat(ChatRequestDto request, UUID userId) {
        String userMessage = extractUserMessage(request);

        List<Map<String, String>> history = convertHistory(request.getConversationHistory());
        List<OrderItemRequestDto> currentOrder = request.getCurrentOrder() != null
                ? request.getCurrentOrder() : new ArrayList<>();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        List<String> userAddresses = user.getAddresses() != null
                ? new ArrayList<>(user.getAddresses()) : new ArrayList<>();
        String selectedAddress = request.getSelectedAddress();

        String systemPrompt = buildSystemPrompt(currentOrder, selectedAddress, userAddresses);
        List<Map<String, String>> recentHistory = history.size() > 4
                ? history.subList(history.size() - 4, history.size())
                : history;
        String llmRawResponse = groqService.chat(systemPrompt, recentHistory, userMessage);

        LlmResponseDto llmResponse = parseLlmResponse(llmRawResponse);

        return processIntent(userMessage, llmResponse, currentOrder, selectedAddress, userAddresses, userId);
    }

    // ========================================
    // Intent 처리 (메인 라우터)
    // ========================================

    @Transactional
    protected ChatResponseDto processIntent(String userMessage, LlmResponseDto llmResponse,
            List<OrderItemRequestDto> currentOrder, String selectedAddress,
            List<String> userAddresses, UUID userId) {

        UserIntent intent = parseIntent(llmResponse.getIntent());
        LlmResponseDto.ExtractedEntities entities = llmResponse.getEntities();

        // 컨텍스트 객체 생성
        IntentContext ctx = new IntentContext();
        ctx.entities = entities;
        ctx.updatedOrder = cartManager.convertToOrderItemDtoList(currentOrder);
        ctx.selectedAddress = selectedAddress;
        ctx.userAddresses = userAddresses;
        ctx.userId = userId;
        ctx.message = llmResponse.getMessage();
        ctx.nextState = OrderFlowState.IDLE;
        ctx.uiAction = UiAction.NONE;
        ctx.storeUpdateBuilder = StoreUpdateDto.builder();
        ctx.pendingItem = findPendingItem(ctx.updatedOrder);
        ctx.pendingIdx = ctx.pendingItem != null ? ctx.updatedOrder.indexOf(ctx.pendingItem) : -1;

        // Intent별 처리
        switch (intent) {
            case ORDER_MENU -> handleOrderMenu(ctx);
            case SELECT_STYLE -> handleSelectStyle(ctx);
            case SET_QUANTITY -> handleSetQuantity(ctx);
            case SELECT_ADDRESS -> handleSelectAddress(ctx);
            case ADD_TO_CART -> handleAddToCart(ctx);
            case SKIP_CUSTOMIZE -> handleSkipCustomize(ctx);
            case CONFIRM_ORDER -> handleConfirmOrder(ctx);
            case EDIT_ORDER -> handleEditOrder(ctx);
            case REMOVE_ITEM -> handleRemoveItem(ctx);
            case CANCEL_ORDER -> handleCancelOrder(ctx);
            case ASK_ORDER_STATUS -> handleAskOrderStatus(ctx);
            case ASK_MENU_INFO -> handleAskMenuInfo(ctx);
            case GREETING -> handleGreeting(ctx);
            case CUSTOMIZE_MENU -> handleCustomizeMenu(ctx);
            case ADD_ADDITIONAL_MENU -> handleAddAdditionalMenu(ctx);
            case SET_MEMO -> handleSetMemo(ctx);
            default -> ctx.nextState = OrderFlowState.IDLE;
        }

        // 응답 생성
        ctx.storeUpdateBuilder.flowState(ctx.nextState);
        if (ctx.selectedAddress != null && !ctx.selectedAddress.isEmpty()) {
            ctx.storeUpdateBuilder.address(ctx.selectedAddress);
        }

        BigDecimal totalPrice = BigDecimal.valueOf(cartManager.calculateTotalPrice(ctx.updatedOrder));

        return ChatResponseDto.builder()
                .userMessage(userMessage)
                .assistantMessage(ctx.message)
                .flowState(ctx.nextState)
                .uiAction(ctx.uiAction)
                .currentOrder(ctx.updatedOrder)
                .totalPrice(totalPrice)
                .selectedAddress(ctx.selectedAddress)
                .userAddresses(userAddresses)
                .storeUpdate(ctx.storeUpdateBuilder.build())
                .specialRequest(ctx.specialRequest)
                .orderId(ctx.orderId)
                .orderNumber(ctx.orderNumber)
                .build();
    }

    // ========================================
    // Intent 핸들러들
    // ========================================

    private void handleOrderMenu(IntentContext ctx) {
        // 주소 확인
        if (ctx.selectedAddress == null || ctx.selectedAddress.isEmpty()) {
            if (!ctx.userAddresses.isEmpty()) {
                ctx.message = "먼저 배달 주소를 선택해주세요!\n" + formatAddressList(ctx.userAddresses);
                ctx.nextState = OrderFlowState.SELECTING_ADDRESS;
                ctx.uiAction = UiAction.REQUEST_ADDRESS;
            } else {
                ctx.message = "저장된 배달 주소가 없어요. 마이페이지에서 주소를 먼저 추가해주세요!";
                ctx.nextState = OrderFlowState.SELECTING_ADDRESS;
            }
            return;
        }

        String menuName = ctx.entities != null ? ctx.entities.getMenuName() : null;
        String styleName = ctx.entities != null ? ctx.entities.getStyleName() : null;
        Integer quantity = ctx.entities != null ? ctx.entities.getQuantity() : null;

        // Case 1: pending 아이템 업데이트
        boolean shouldUpdatePending = ctx.pendingItem != null && (
                menuName == null ||
                menuMatcher.isMatchingMenu(ctx.pendingItem.getDinnerName(), menuName) ||
                ctx.pendingItem.getDinnerName().equalsIgnoreCase(menuName)
        );

        if (shouldUpdatePending && (styleName != null || quantity != null)) {
            updatePendingItemWithStyleOrQuantity(ctx, styleName, quantity);
            return;
        }

        // Case 2: 새 메뉴 추가
        if (menuName != null) {
            addNewDinnerMenu(ctx, menuName, styleName, quantity);
            return;
        }

        // Case 3: 정보 부족
        handleIncompleteOrderInfo(ctx);
    }

    private void handleSelectStyle(IntentContext ctx) {
        if (ctx.entities == null || ctx.entities.getStyleName() == null) {
            ctx.message = "먼저 디너를 선택해주세요!";
            ctx.nextState = OrderFlowState.SELECTING_MENU;
            return;
        }

        var styleOpt = menuMatcher.findStyleByName(ctx.entities.getStyleName());
        if (styleOpt.isEmpty()) {
            ctx.message = "'" + ctx.entities.getStyleName() + "' 스타일을 찾을 수 없어요. 심플, 그랜드, 디럭스 중에서 선택해주세요.";
            ctx.nextState = OrderFlowState.SELECTING_STYLE;
            return;
        }

        ServingStyleResponseDto style = styleOpt.get();

        // 스타일이 없는 모든 pending 아이템 찾기
        List<Integer> pendingIndices = new ArrayList<>();
        for (int i = 0; i < ctx.updatedOrder.size(); i++) {
            OrderItemDto item = ctx.updatedOrder.get(i);
            if (item.getServingStyleId() == null || item.getServingStyleId().isEmpty()) {
                pendingIndices.add(i);
            }
        }

        if (pendingIndices.isEmpty()) {
            ctx.message = "스타일을 적용할 디너가 없어요. 먼저 디너를 선택해주세요!";
            ctx.nextState = OrderFlowState.SELECTING_MENU;
            return;
        }

        // 스타일 호환성 체크
        OrderItemDto firstPending = ctx.updatedOrder.get(pendingIndices.get(0));
        if (!menuMatcher.isStyleAvailableForDinner(firstPending.getDinnerName(), style.getStyleName())) {
            String koreanDinner = menuMatcher.toKoreanDinnerName(firstPending.getDinnerName());
            String availableStyles = menuMatcher.getAvailableStylesForDinner(firstPending.getDinnerName());
            ctx.message = koreanDinner + "는 심플 스타일을 선택할 수 없어요. " + availableStyles + " 중에서 선택해주세요!";
            ctx.nextState = OrderFlowState.SELECTING_STYLE;
            return;
        }

        // 모든 pending 아이템에 스타일 적용
        applyStyleToAllPendingItems(ctx, pendingIndices, style);
    }

    private void handleSetQuantity(IntentContext ctx) {
        if (ctx.entities == null || ctx.entities.getQuantity() == null || ctx.pendingItem == null) {
            ctx.message = "먼저 메뉴를 선택해주세요!";
            ctx.nextState = OrderFlowState.SELECTING_MENU;
            return;
        }

        if (ctx.pendingItem.getServingStyleId() == null) {
            ctx.message = "먼저 스타일을 선택해주세요! Simple, Grand, Deluxe 중에서 골라주세요.";
            ctx.nextState = OrderFlowState.SELECTING_STYLE;
            return;
        }

        OrderItemDto updated = cartManager.setQuantity(ctx.pendingItem, ctx.entities.getQuantity());
        ctx.updatedOrder.set(ctx.pendingIdx, updated);
        ctx.nextState = OrderFlowState.ASKING_MORE;
        ctx.uiAction = UiAction.UPDATE_ORDER_LIST;
        ctx.message = updated.getDinnerName() + " " + ctx.entities.getQuantity() + "개 주문 완료! 더 주문하실 게 있으세요?";
    }

    private void handleSelectAddress(IntentContext ctx) {
        if (ctx.entities == null || ctx.entities.getAddressIndex() == null) return;

        int idx = ctx.entities.getAddressIndex() - 1;
        if (idx < 0 || idx >= ctx.userAddresses.size()) {
            ctx.message = "올바른 주소 번호를 선택해주세요. (1~" + ctx.userAddresses.size() + ")";
            ctx.nextState = OrderFlowState.SELECTING_ADDRESS;
            ctx.uiAction = UiAction.REQUEST_ADDRESS;
            return;
        }

        ctx.selectedAddress = ctx.userAddresses.get(idx);
        ctx.storeUpdateBuilder.address(ctx.selectedAddress);

        boolean allComplete = !ctx.updatedOrder.isEmpty() &&
                ctx.updatedOrder.stream().allMatch(item ->
                        item.getServingStyleId() != null && item.getQuantity() > 0);

        if (allComplete) {
            ctx.nextState = OrderFlowState.READY_TO_CHECKOUT;
            ctx.uiAction = UiAction.SHOW_CONFIRM_MODAL;
            ctx.message = ctx.selectedAddress + "로 배달해드릴게요! 주문을 확정하시겠어요?";
        } else if (ctx.updatedOrder.isEmpty()) {
            ctx.nextState = OrderFlowState.SELECTING_MENU;
            ctx.message = ctx.selectedAddress + "로 배달 주소 설정 완료! 어떤 메뉴를 주문하시겠어요?";
        } else {
            ctx.nextState = OrderFlowState.ASKING_MORE;
            ctx.message = ctx.selectedAddress + "로 배달해드릴게요!";
        }
    }

    private void handleAddToCart(IntentContext ctx) {
        // ★ LLM이 ADD_TO_CART와 함께 메뉴 정보를 보냈으면 ORDER_MENU처럼 처리
        String menuName = ctx.entities != null ? ctx.entities.getMenuName() : null;
        String styleName = ctx.entities != null ? ctx.entities.getStyleName() : null;
        Integer quantity = ctx.entities != null ? ctx.entities.getQuantity() : null;

        if (menuName != null && !menuName.isEmpty()) {
            // ★ 새 메뉴 추가 요청 → ORDER_MENU 로직 재사용
            addNewDinnerMenu(ctx, menuName, styleName, quantity);
            return;
        }

        // 불완전한 아이템 정리
        ctx.updatedOrder.removeIf(item -> item.getQuantity() == 0 || item.getServingStyleId() == null);

        if (ctx.selectedAddress == null || ctx.selectedAddress.isEmpty()) {
            if (!ctx.userAddresses.isEmpty()) {
                ctx.message = "배달 주소를 선택해주세요!\n" + formatAddressList(ctx.userAddresses);
                ctx.nextState = OrderFlowState.SELECTING_ADDRESS;
                ctx.uiAction = UiAction.REQUEST_ADDRESS;
            } else {
                ctx.message = "저장된 주소가 없어요. 마이페이지에서 주소를 추가해주세요.";
                ctx.nextState = OrderFlowState.SELECTING_ADDRESS;
            }
        } else {
            ctx.nextState = OrderFlowState.SELECTING_MENU;
            ctx.uiAction = UiAction.UPDATE_ORDER_LIST;
            int currentTotal = cartManager.calculateTotalPrice(ctx.updatedOrder);
            ctx.message = String.format("현재까지 총 %,d원이에요.\n어떤 디너를 더 추가하시겠어요?\n" +
                    "더 이상 없으시면 '결제할게요'라고 말씀해주세요.", currentTotal);
        }
    }

    private void handleSkipCustomize(IntentContext ctx) {
        ctx.updatedOrder.removeIf(item -> item.getQuantity() == 0 || item.getServingStyleId() == null);

        if (ctx.updatedOrder.isEmpty()) {
            ctx.message = "장바구니가 비어있어요. 먼저 디너를 선택해주세요!";
            ctx.nextState = OrderFlowState.SELECTING_MENU;
        } else if (ctx.selectedAddress == null || ctx.selectedAddress.isEmpty()) {
            if (!ctx.userAddresses.isEmpty()) {
                ctx.message = "배달 주소를 선택해주세요!\n" + formatAddressList(ctx.userAddresses);
                ctx.nextState = OrderFlowState.SELECTING_ADDRESS;
                ctx.uiAction = UiAction.REQUEST_ADDRESS;
            } else {
                ctx.message = "저장된 주소가 없어요. 마이페이지에서 주소를 추가해주세요.";
                ctx.nextState = OrderFlowState.SELECTING_ADDRESS;
            }
        } else {
            ctx.nextState = OrderFlowState.READY_TO_CHECKOUT;
            ctx.uiAction = UiAction.SHOW_CONFIRM_MODAL;
            ctx.message = "기본 구성으로 진행할게요! 주문 내역을 확인해주세요.\n" +
                    buildOrderSummary(ctx.updatedOrder, ctx.selectedAddress) +
                    "\n결제를 진행하시겠어요?";
        }
    }

    private void handleConfirmOrder(IntentContext ctx) {
        // 특별 요청사항 저장
        String memoFromConfirm = ctx.entities != null ? ctx.entities.getSpecialRequest() : null;
        if (memoFromConfirm != null && !memoFromConfirm.isEmpty()) {
            ctx.specialRequest = memoFromConfirm;
            ctx.storeUpdateBuilder.memo(memoFromConfirm);
        }

        ctx.updatedOrder.removeIf(item -> item.getQuantity() == 0 || item.getServingStyleId() == null);

        if (ctx.updatedOrder.isEmpty()) {
            ctx.message = "주문할 상품이 없어요. 먼저 메뉴를 선택해주세요!";
            ctx.nextState = OrderFlowState.SELECTING_MENU;
            return;
        }

        if (ctx.selectedAddress == null || ctx.selectedAddress.isEmpty()) {
            ctx.message = "배달 주소를 먼저 선택해주세요!";
            ctx.nextState = OrderFlowState.SELECTING_ADDRESS;
            ctx.uiAction = UiAction.REQUEST_ADDRESS;
            return;
        }

        try {
            OrderResponseDto orderResult = processCheckout(ctx.updatedOrder, ctx.selectedAddress, ctx.userId);
            ctx.orderId = orderResult.getId();
            ctx.orderNumber = orderResult.getOrderNumber();

            ctx.message = String.format(
                    "주문이 완료되었습니다! 🎉\n\n" +
                    "주문 번호: %s\n" +
                    "총 결제 금액: %,d원\n\n" +
                    "감사합니다! 맛있는 식사 되세요!",
                    ctx.orderNumber, orderResult.getGrandTotal().intValue());

            ctx.updatedOrder.clear();
            ctx.nextState = OrderFlowState.IDLE;
            ctx.uiAction = UiAction.ORDER_COMPLETED;
        } catch (Exception e) {
            log.error("주문 처리 실패: {}", e.getMessage());
            ctx.message = "주문 처리 중 오류가 발생했습니다: " + e.getMessage();
            ctx.nextState = OrderFlowState.READY_TO_CHECKOUT;
        }
    }

    private void handleEditOrder(IntentContext ctx) {
        if (ctx.entities == null || ctx.entities.getMenuName() == null) {
            ctx.message = "어떤 메뉴를 수정할까요? 메뉴 이름을 말씀해주세요.";
            ctx.nextState = OrderFlowState.ASKING_MORE;
            return;
        }

        int targetIdx = findOrderItemIndex(ctx.updatedOrder, ctx.entities.getMenuName());
        if (targetIdx < 0) {
            ctx.message = "'" + ctx.entities.getMenuName() + "' 메뉴가 장바구니에 없어요.";
            ctx.nextState = OrderFlowState.ASKING_MORE;
            return;
        }

        OrderItemDto target = ctx.updatedOrder.get(targetIdx);

        if (ctx.entities.getQuantity() != null && ctx.entities.getQuantity() > 0) {
            target = cartManager.setQuantity(target, ctx.entities.getQuantity());
            ctx.updatedOrder.set(targetIdx, target);
            ctx.message = target.getDinnerName() + " " + ctx.entities.getQuantity() + "개로 변경했어요!";
            ctx.uiAction = UiAction.UPDATE_ORDER_LIST;
        }

        if (ctx.entities.getStyleName() != null) {
            var styleOpt = menuMatcher.findStyleByName(ctx.entities.getStyleName());
            if (styleOpt.isPresent()) {
                target = cartManager.changeStyle(target, styleOpt.get());
                ctx.updatedOrder.set(targetIdx, target);
                ctx.message = target.getDinnerName() + " 스타일을 " + styleOpt.get().getStyleName() + "로 변경했어요!";
                ctx.uiAction = UiAction.UPDATE_ORDER_LIST;
            }
        }

        ctx.nextState = OrderFlowState.ASKING_MORE;
    }

    private void handleRemoveItem(IntentContext ctx) {
        if (ctx.updatedOrder.isEmpty()) {
            ctx.message = "장바구니가 비어있어요!";
            ctx.nextState = OrderFlowState.SELECTING_MENU;
            return;
        }

        if (ctx.entities == null || ctx.entities.getMenuName() == null) return;

        String menuName = ctx.entities.getMenuName();
        if ("LAST".equalsIgnoreCase(menuName)) {
            OrderItemDto removed = ctx.updatedOrder.remove(ctx.updatedOrder.size() - 1);
            ctx.message = removed.getDinnerName() + "을(를) 삭제했어요!";
        } else {
            int targetIdx = findOrderItemIndex(ctx.updatedOrder, menuName);
            if (targetIdx >= 0) {
                OrderItemDto removed = ctx.updatedOrder.remove(targetIdx);
                ctx.message = removed.getDinnerName() + "을(를) 삭제했어요!";
            } else {
                ctx.message = "'" + menuName + "' 메뉴가 장바구니에 없어요.";
            }
        }

        ctx.uiAction = UiAction.UPDATE_ORDER_LIST;
        ctx.nextState = ctx.updatedOrder.isEmpty() ? OrderFlowState.SELECTING_MENU : OrderFlowState.ASKING_MORE;
    }

    private void handleCancelOrder(IntentContext ctx) {
        ctx.updatedOrder.clear();
        ctx.selectedAddress = null;
        ctx.storeUpdateBuilder.address(null);
        ctx.nextState = OrderFlowState.IDLE;
        ctx.uiAction = UiAction.SHOW_CANCEL_CONFIRM;
        ctx.message = "주문이 취소되었어요. 새로운 주문을 시작해주세요!";
    }

    private void handleAskOrderStatus(IntentContext ctx) {
        if (ctx.updatedOrder.isEmpty()) {
            ctx.message = "현재 장바구니가 비어있어요. 디너를 선택해주세요!";
        } else {
            ctx.message = "현재 주문 내역이에요:\n" + buildOrderSummary(ctx.updatedOrder, ctx.selectedAddress);
        }
        ctx.nextState = OrderFlowState.ASKING_MORE;
    }

    private void handleAskMenuInfo(IntentContext ctx) {
        if (ctx.selectedAddress == null || ctx.selectedAddress.isEmpty()) {
            if (!ctx.userAddresses.isEmpty()) {
                ctx.message = ctx.message + "\n\n배달 주소를 먼저 선택해주세요!\n" + formatAddressList(ctx.userAddresses);
                ctx.nextState = OrderFlowState.SELECTING_ADDRESS;
                ctx.uiAction = UiAction.REQUEST_ADDRESS;
            } else {
                ctx.nextState = OrderFlowState.SELECTING_ADDRESS;
            }
        } else {
            ctx.nextState = OrderFlowState.SELECTING_MENU;
        }
    }

    private void handleGreeting(IntentContext ctx) {
        if (ctx.selectedAddress == null || ctx.selectedAddress.isEmpty()) {
            if (!ctx.userAddresses.isEmpty()) {
                ctx.message = "안녕하세요! 미스터대백 프리미엄 디너 배달 서비스입니다. 먼저 배달받으실 주소를 선택해주세요!\n"
                        + formatAddressList(ctx.userAddresses);
                ctx.nextState = OrderFlowState.SELECTING_ADDRESS;
                ctx.uiAction = UiAction.REQUEST_ADDRESS;
            } else {
                ctx.message = "안녕하세요! 미스터대백입니다. 저장된 배달 주소가 없어요. 마이페이지에서 주소를 먼저 추가해주세요!";
                ctx.nextState = OrderFlowState.SELECTING_ADDRESS;
            }
        } else {
            ctx.message = "안녕하세요! 미스터대백입니다. 배달 주소는 '" + ctx.selectedAddress + "'로 설정되어 있어요. 어떤 디너를 주문하시겠어요?";
            ctx.nextState = OrderFlowState.SELECTING_MENU;
        }
    }

    private void handleCustomizeMenu(IntentContext ctx) {
        if (ctx.updatedOrder.isEmpty()) {
            ctx.message = "장바구니가 비어있어요. 먼저 디너를 선택해주세요!";
            ctx.nextState = OrderFlowState.SELECTING_MENU;
            return;
        }

        String menuItemName = ctx.entities != null ? ctx.entities.getMenuItemName() : null;
        String action = ctx.entities != null ? ctx.entities.getAction() : null;
        Integer menuItemQuantity = ctx.entities != null ? ctx.entities.getMenuItemQuantity() : null;

        if (menuItemName != null && action != null) {
            processMenuItemCustomization(ctx, menuItemName, action, menuItemQuantity);
        } else {
            showCustomizationOptions(ctx);
        }
    }

    private void handleAddAdditionalMenu(IntentContext ctx) {
        String additionalMenuName = ctx.entities != null ? ctx.entities.getMenuItemName() : null;
        Integer additionalQty = ctx.entities != null ? ctx.entities.getMenuItemQuantity() : 1;
        if (additionalQty == null || additionalQty <= 0) additionalQty = 1;

        if (additionalMenuName == null || additionalMenuName.isEmpty()) {
            ctx.message = "어떤 메뉴를 추가 주문하시겠어요? (예: '샐러드 추가', '와인 2개 추가')";
            ctx.nextState = OrderFlowState.CUSTOMIZING;
            return;
        }

        // ★ 메뉴 아이템 찾기 (이름으로 검색)
        var menuItemOpt = menuItemsRepository.findAll().stream()
                .filter(m -> m.getName().contains(additionalMenuName) ||
                        additionalMenuName.contains(m.getName()))
                .findFirst();

        if (menuItemOpt.isEmpty()) {
            ctx.message = "'" + additionalMenuName + "' 메뉴를 찾을 수 없어요. 다른 메뉴를 말씀해주세요.";
            ctx.nextState = OrderFlowState.CUSTOMIZING;
            return;
        }

        var menuItem = menuItemOpt.get();
        int unitPrice = menuItem.getUnitPrice().intValue();
        int totalPrice = unitPrice * additionalQty;

        try {
            // ★ 추가 메뉴 Product 생성
            var request = new com.saeal.MrDaebackService.product.dto.request.CreateAdditionalMenuProductRequest();
            request.setMenuItemId(menuItem.getId().toString());
            request.setQuantity(additionalQty);
            request.setAddress(ctx.selectedAddress);
            request.setMemo("");

            ProductResponseDto product = productService.createAdditionalMenuProduct(request);

            // ★ OrderItemDto로 변환하여 updatedOrder에 추가
            OrderItemDto additionalItem = OrderItemDto.builder()
                    .dinnerId(null)
                    .dinnerName("추가 메뉴: " + menuItem.getName())
                    .servingStyleId("ADDITIONAL")  // 추가 메뉴 표시
                    .servingStyleName("추가 메뉴")
                    .quantity(additionalQty)
                    .totalPrice(totalPrice)
                    .productId(product.getId())
                    .build();

            ctx.updatedOrder.add(additionalItem);

            int grandTotal = cartManager.calculateTotalPrice(ctx.updatedOrder);
            ctx.message = String.format("%s %d개 추가 완료! (+%,d원)\n" +
                    "현재 총 금액: %,d원\n\n" +
                    "다른 메뉴도 추가하시겠어요? 완료하시면 '결제할게요'라고 말씀해주세요.",
                    menuItem.getName(), additionalQty, totalPrice, grandTotal);

            ctx.uiAction = UiAction.UPDATE_ORDER_LIST;
            ctx.nextState = OrderFlowState.ASKING_MORE;

        } catch (Exception e) {
            log.error("[ADD_ADDITIONAL_MENU] 추가 메뉴 생성 실패: {}", e.getMessage());
            ctx.message = "추가 메뉴 생성에 실패했어요: " + e.getMessage();
            ctx.nextState = OrderFlowState.CUSTOMIZING;
        }
    }

    private void handleSetMemo(IntentContext ctx) {
        String memo = ctx.entities != null ? ctx.entities.getSpecialRequest() : null;
        if (memo != null && !memo.isEmpty()) {
            ctx.specialRequest = memo;
            ctx.storeUpdateBuilder.memo(memo);
            ctx.message = "특별 요청사항이 저장되었어요: \"" + memo + "\"\n결제를 진행하시겠어요?";
            ctx.nextState = OrderFlowState.READY_TO_CHECKOUT;
            ctx.uiAction = UiAction.SHOW_CONFIRM_MODAL;
        } else {
            ctx.message = "어떤 요청사항을 추가하시겠어요? (예: '알러지가 있어요', '덜 맵게 해주세요')";
            ctx.nextState = OrderFlowState.CUSTOMIZING;
        }
    }

    // ========================================
    // 헬퍼 메서드들
    // ========================================

    private void updatePendingItemWithStyleOrQuantity(IntentContext ctx, String styleName, Integer quantity) {
        OrderItemDto updated = ctx.pendingItem;

        if (styleName != null) {
            var styleOpt = menuMatcher.findStyleByName(styleName);
            if (styleOpt.isPresent()) {
                ServingStyleResponseDto style = styleOpt.get();

                if (!menuMatcher.isStyleAvailableForDinner(updated.getDinnerName(), style.getStyleName())) {
                    String koreanDinner = menuMatcher.toKoreanDinnerName(updated.getDinnerName());
                    String availableStyles = menuMatcher.getAvailableStylesForDinner(updated.getDinnerName());
                    ctx.message = koreanDinner + "는 심플 스타일을 선택할 수 없어요. " + availableStyles + " 중에서 선택해주세요!";
                    ctx.nextState = OrderFlowState.SELECTING_STYLE;
                    return;
                }

                updated = cartManager.applyStyleToItem(updated, style);

                if (ctx.selectedAddress != null && !ctx.selectedAddress.isEmpty()) {
                    try {
                        var dinnerOpt = menuMatcher.findDinnerByName(updated.getDinnerName());
                        if (dinnerOpt.isPresent()) {
                            ProductResponseDto product = createProductForVoiceOrder(
                                    dinnerOpt.get().getId().toString(),
                                    style.getId().toString(),
                                    quantity != null && quantity > 0 ? quantity : 1,
                                    ctx.selectedAddress);

                            updated = cartManager.setProductInfo(updated, product);
                            ctx.storeUpdateBuilder.stylesToSet(List.of(
                                    buildStyleSetDto(dinnerOpt.get(), style, product, ctx.pendingIdx)));
                        }
                    } catch (Exception e) {
                        log.error("Product 생성 실패: {}", e.getMessage());
                    }
                }
            }
        }

        if (quantity != null && quantity > 0) {
            if (updated.getServingStyleId() == null) {
                ctx.message = "먼저 스타일을 선택해주세요! Simple, Grand, Deluxe 중에서 골라주세요.";
                ctx.nextState = OrderFlowState.SELECTING_STYLE;
                return;
            }
            updated = cartManager.setQuantity(updated, quantity);
        }

        ctx.updatedOrder.set(ctx.pendingIdx, updated);
        ctx.uiAction = UiAction.UPDATE_ORDER_LIST;

        // 다음 상태 결정
        if (updated.getServingStyleId() == null) {
            ctx.nextState = OrderFlowState.SELECTING_STYLE;
            String koreanDinner = menuMatcher.toKoreanDinnerName(updated.getDinnerName());
            String availableStyles = menuMatcher.getAvailableStylesForDinner(updated.getDinnerName());
            ctx.message = koreanDinner + " 스타일을 선택해주세요. (" + availableStyles + ")";
        } else if (updated.getQuantity() == 0) {
            ctx.nextState = OrderFlowState.SELECTING_QUANTITY;
            String koreanStyle = menuMatcher.toKoreanStyleName(updated.getServingStyleName());
            ctx.message = koreanStyle + "로 선택하셨어요! 몇 개로 드릴까요?";
        } else {
            ctx.nextState = OrderFlowState.ASKING_MORE;
            String koreanDinner = menuMatcher.toKoreanDinnerName(updated.getDinnerName());
            String koreanStyle = menuMatcher.toKoreanStyleName(updated.getServingStyleName());
            int currentTotal = cartManager.calculateTotalPrice(ctx.updatedOrder);
            ctx.message = koreanDinner + " " + koreanStyle + " " + updated.getQuantity() + "개 추가 완료!\n" +
                    String.format("현재까지 총 %,d원이에요.\n\n", currentTotal) +
                    "다른 디너를 더 추가하시겠어요?\n" +
                    "• 더 추가하려면: '잉글리시 디너 추가', '프렌치 디너 2개'\n" +
                    "• 커스터마이징: '커스터마이징 할래', '메뉴 수정할래'\n" +
                    "• 주문 완료: '결제할게요', '주문 확정'";
        }
    }

    private void addNewDinnerMenu(IntentContext ctx, String menuName, String styleName, Integer quantity) {
        // 진행 중인 아이템이 있으면 먼저 완성하도록 안내
        if (ctx.pendingItem != null) {
            if (ctx.pendingItem.getServingStyleId() == null) {
                ctx.message = ctx.pendingItem.getDinnerName() + "의 스타일을 먼저 선택해주세요!";
                ctx.nextState = OrderFlowState.SELECTING_STYLE;
                return;
            } else if (ctx.pendingItem.getQuantity() == 0) {
                ctx.message = ctx.pendingItem.getDinnerName() + "의 수량을 먼저 선택해주세요!";
                ctx.nextState = OrderFlowState.SELECTING_QUANTITY;
                return;
            }
        }

        var dinnerOpt = menuMatcher.findDinnerByName(menuName);
        if (dinnerOpt.isEmpty()) {
            ctx.message = "죄송해요, '" + menuName + "' 메뉴를 찾을 수 없어요. 다른 메뉴를 말씀해주세요.";
            ctx.nextState = OrderFlowState.SELECTING_MENU;
            return;
        }

        DinnerResponseDto dinner = dinnerOpt.get();
        ServingStyleResponseDto selectedStyle = null;

        if (styleName != null) {
            var styleOpt = menuMatcher.findStyleByName(styleName);
            if (styleOpt.isPresent() && menuMatcher.isStyleAvailableForDinner(dinner.getDinnerName(), styleOpt.get().getStyleName())) {
                selectedStyle = styleOpt.get();
            }
        }

        int finalQuantity = (quantity != null && quantity > 0) ? quantity : 1;
        List<StoreUpdateDto.DinnerAddDto> dinnersToAdd = new ArrayList<>();
        List<StoreUpdateDto.StyleSetDto> stylesToSet = new ArrayList<>();

        for (int i = 0; i < finalQuantity; i++) {
            OrderItemDto newItem = cartManager.addMenu(dinner, 1);

            if (selectedStyle != null) {
                newItem = cartManager.applyStyleToItem(newItem, selectedStyle);

                if (ctx.selectedAddress != null && !ctx.selectedAddress.isEmpty()) {
                    try {
                        ProductResponseDto product = createProductForVoiceOrder(
                                dinner.getId().toString(),
                                selectedStyle.getId().toString(),
                                1,
                                ctx.selectedAddress);
                        newItem = cartManager.setProductInfo(newItem, product);
                        int newItemIdx = ctx.updatedOrder.size();
                        stylesToSet.add(buildStyleSetDto(dinner, selectedStyle, product, newItemIdx));
                    } catch (Exception e) {
                        log.error("Product 생성 실패 ({}번째): {}", i + 1, e.getMessage());
                    }
                }
            }

            ctx.updatedOrder.add(newItem);
            dinnersToAdd.add(StoreUpdateDto.DinnerAddDto.builder()
                    .dinnerId(dinner.getId().toString())
                    .dinnerName(dinner.getDinnerName())
                    .description(dinner.getDescription())
                    .basePrice(dinner.getBasePrice())
                    .quantity(1)
                    .build());
        }

        ctx.uiAction = UiAction.UPDATE_ORDER_LIST;
        ctx.storeUpdateBuilder.dinnersToAdd(dinnersToAdd);
        if (!stylesToSet.isEmpty()) {
            ctx.storeUpdateBuilder.stylesToSet(stylesToSet);
        }

        if (selectedStyle == null) {
            ctx.nextState = OrderFlowState.SELECTING_STYLE;
            String koreanDinner = menuMatcher.toKoreanDinnerName(dinner.getDinnerName());
            String availableStyles = menuMatcher.getAvailableStylesForDinner(dinner.getDinnerName());
            ctx.message = koreanDinner + " " + finalQuantity + "개 선택! 스타일을 선택해주세요. (" + availableStyles + ")";
        } else {
            ctx.nextState = OrderFlowState.ASKING_MORE;
            String koreanDinner = menuMatcher.toKoreanDinnerName(dinner.getDinnerName());
            String koreanStyle = menuMatcher.toKoreanStyleName(selectedStyle.getStyleName());
            int currentTotal = cartManager.calculateTotalPrice(ctx.updatedOrder);
            ctx.message = koreanDinner + " " + koreanStyle + " " + finalQuantity + "개 추가 완료!\n" +
                    String.format("현재까지 총 %,d원이에요.\n\n", currentTotal) +
                    "다른 디너를 더 추가하시겠어요?\n" +
                    "• 더 추가하려면: '잉글리시 디너 추가', '프렌치 디너 2개'\n" +
                    "• 커스터마이징: '커스터마이징 할래', '메뉴 수정할래'\n" +
                    "• 주문 완료: '결제할게요', '주문 확정'";
        }
    }

    private void handleIncompleteOrderInfo(IntentContext ctx) {
        if (ctx.pendingItem != null) {
            if (ctx.pendingItem.getServingStyleId() == null) {
                ctx.message = ctx.pendingItem.getDinnerName() + " 스타일은 Simple, Grand, Deluxe 중 어떤 걸로 하실래요?";
                ctx.nextState = OrderFlowState.SELECTING_STYLE;
            } else {
                ctx.message = ctx.pendingItem.getDinnerName() + " 몇 개로 드릴까요?";
                ctx.nextState = OrderFlowState.SELECTING_QUANTITY;
            }
        } else {
            ctx.message = "어떤 메뉴를 주문하시겠어요?";
            ctx.nextState = OrderFlowState.SELECTING_MENU;
        }
    }

    private void applyStyleToAllPendingItems(IntentContext ctx, List<Integer> pendingIndices, ServingStyleResponseDto style) {
        List<StoreUpdateDto.StyleSetDto> stylesToSet = new ArrayList<>();
        int processedCount = 0;
        String dinnerNameForMessage = "";

        for (int idx : pendingIndices) {
            OrderItemDto item = ctx.updatedOrder.get(idx);
            OrderItemDto updated = cartManager.applyStyleToItem(item, style);
            updated = cartManager.setQuantity(updated, 1);

            if (ctx.selectedAddress != null && !ctx.selectedAddress.isEmpty()) {
                try {
                    var dinnerOpt = menuMatcher.findDinnerByName(updated.getDinnerName());
                    if (dinnerOpt.isPresent()) {
                        ProductResponseDto product = createProductForVoiceOrder(
                                dinnerOpt.get().getId().toString(),
                                style.getId().toString(),
                                1,
                                ctx.selectedAddress);

                        updated = cartManager.setProductInfo(updated, product);
                        stylesToSet.add(buildStyleSetDto(dinnerOpt.get(), style, product, idx));
                        dinnerNameForMessage = dinnerOpt.get().getDinnerName();
                    }
                } catch (Exception e) {
                    log.error("Product 생성 실패 ({}번째): {}", processedCount + 1, e.getMessage());
                }
            }

            ctx.updatedOrder.set(idx, updated);
            processedCount++;
        }

        if (!stylesToSet.isEmpty()) {
            ctx.storeUpdateBuilder.stylesToSet(stylesToSet);
        }

        ctx.uiAction = UiAction.UPDATE_ORDER_LIST;
        ctx.nextState = OrderFlowState.ASKING_MORE;

        String koreanDinner = menuMatcher.toKoreanDinnerName(dinnerNameForMessage);
        String koreanStyle = menuMatcher.toKoreanStyleName(style.getStyleName());
        int currentTotal = cartManager.calculateTotalPrice(ctx.updatedOrder);

        ctx.message = koreanDinner + " " + koreanStyle + " " + processedCount + "개 추가 완료!\n" +
                String.format("현재까지 총 %,d원이에요.\n\n", currentTotal) +
                "다른 디너를 더 추가하시겠어요?\n" +
                "• 더 추가하려면: '잉글리시 디너 추가', '프렌치 디너 2개'\n" +
                "• 커스터마이징: '커스터마이징 할래', '메뉴 수정할래'\n" +
                "• 주문 완료: '결제할게요', '주문 확정'";
    }

    private void processMenuItemCustomization(IntentContext ctx, String menuItemName, String action, Integer menuItemQuantity) {
        boolean found = false;
        for (int i = 0; i < ctx.updatedOrder.size(); i++) {
            OrderItemDto item = ctx.updatedOrder.get(i);
            if (item.getMenuItems() == null || item.getMenuItems().isEmpty()) continue;

            var menuItem = cartManager.findMenuItemByName(item, menuItemName);
            if (menuItem != null) {
                int currentQty = menuItem.getCurrentQuantity();
                int newQty = currentQty;

                // ★ 표준화된 액션 코드 처리 (LLM이 ADD/REMOVE/SET 중 하나를 반환)
                String actionCode = action.toUpperCase();
                switch (actionCode) {
                    case "ADD":
                        newQty = currentQty + (menuItemQuantity != null ? menuItemQuantity : 1);
                        break;
                    case "REMOVE":
                        newQty = Math.max(0, currentQty - (menuItemQuantity != null ? menuItemQuantity : 1));
                        break;
                    case "SET":
                        newQty = menuItemQuantity != null ? menuItemQuantity : currentQty;
                        break;
                    default:
                        // Fallback: menuItemQuantity가 있으면 SET으로 처리
                        if (menuItemQuantity != null) {
                            newQty = menuItemQuantity;
                        }
                        break;
                }

                OrderItemDto updated = cartManager.updateMenuItemQuantity(item, menuItem.getMenuItemId(), newQty);
                ctx.updatedOrder.set(i, updated);
                found = true;

                // DB 업데이트
                if (item.getProductId() != null && !item.getProductId().isEmpty()) {
                    try {
                        UpdateProductMenuItemRequest updateRequest = new UpdateProductMenuItemRequest();
                        updateRequest.setQuantity(Math.max(0, newQty));
                        productService.updateProductMenuItem(
                                UUID.fromString(item.getProductId()),
                                UUID.fromString(menuItem.getMenuItemId()),
                                updateRequest);
                    } catch (Exception e) {
                        log.error("[CUSTOMIZE_MENU] DB 업데이트 실패: {}", e.getMessage());
                    }
                }

                String koreanDinner = menuMatcher.toKoreanDinnerName(item.getDinnerName());
                int priceDiff = updated.getTotalPrice() - item.getTotalPrice();
                String priceChange = priceDiff > 0 ? String.format("+%,d원", priceDiff) :
                        priceDiff < 0 ? String.format("%,d원", priceDiff) : "";

                ctx.message = String.format("%s의 %s을(를) %d개로 변경했어요! %s\n총 금액: %,d원\n" +
                        "다른 항목도 변경하시겠어요?",
                        koreanDinner, menuItem.getMenuItemName(), newQty, priceChange,
                        cartManager.calculateTotalPrice(ctx.updatedOrder));

                ctx.uiAction = UiAction.UPDATE_ORDER_LIST;
                break;
            }
        }

        if (!found) {
            ctx.message = "'" + menuItemName + "' 항목을 찾을 수 없어요. 주문하신 디너의 구성품 중에서 선택해주세요.";
        }

        ctx.nextState = OrderFlowState.CUSTOMIZING;
    }

    private void showCustomizationOptions(IntentContext ctx) {
        StringBuilder menuItemsInfo = new StringBuilder();
        menuItemsInfo.append("커스터마이징을 시작합니다!\n\n");

        for (OrderItemDto item : ctx.updatedOrder) {
            if (item.getMenuItems() != null && !item.getMenuItems().isEmpty()) {
                String koreanDinner = menuMatcher.toKoreanDinnerName(item.getDinnerName());
                menuItemsInfo.append("【").append(koreanDinner).append("】\n");
                for (var mi : item.getMenuItems()) {
                    String priceInfo = mi.getUnitPrice() > 0 ? String.format(" (%,d원/개)", mi.getUnitPrice()) : "";
                    menuItemsInfo.append(String.format("  • %s: %d개%s\n",
                            mi.getMenuItemName(), mi.getCurrentQuantity(), priceInfo));
                }
                menuItemsInfo.append("\n");
            }
        }

        if (menuItemsInfo.length() > 50) {
            menuItemsInfo.append("변경하실 항목을 말씀해주세요!\n");
            menuItemsInfo.append("(예: '스테이크 1개 추가', '와인 빼줘', '샐러드 2개로 해줘')");
            ctx.message = menuItemsInfo.toString();
            ctx.nextState = OrderFlowState.CUSTOMIZING;
        } else {
            boolean hasCompletedItems = ctx.updatedOrder.stream()
                    .anyMatch(item -> item.getServingStyleId() != null && item.getProductId() != null);

            if (hasCompletedItems) {
                ctx.message = "커스터마이징을 시작합니다! 어떤 메뉴를 변경하시겠어요?\n(예: '스테이크 1개 추가', '와인 빼줘')";
                ctx.nextState = OrderFlowState.CUSTOMIZING;
            } else {
                ctx.message = "커스터마이징할 항목이 없어요. 먼저 디너의 스타일을 선택해주세요!";
                ctx.nextState = OrderFlowState.SELECTING_STYLE;
            }
        }
    }

    // ========================================
    // Product 생성 및 결제 처리
    // ========================================

    private ProductResponseDto createProductForVoiceOrder(String dinnerId, String styleId, int quantity, String address) {
        CreateProductRequest request = new CreateProductRequest();
        request.setDinnerId(dinnerId);
        request.setServingStyleId(styleId);
        request.setQuantity(quantity);
        request.setAddress(address);
        request.setMemo("");
        return productService.createProduct(request);
    }

    @Transactional
    protected OrderResponseDto processCheckout(List<OrderItemDto> orderItems, String deliveryAddress, UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        List<Product> products = new ArrayList<>();
        for (OrderItemDto item : orderItems) {
            // ★ 추가 메뉴는 servingStyleId가 "ADDITIONAL"
            boolean isAdditionalMenu = "ADDITIONAL".equals(item.getServingStyleId());

            if (!isAdditionalMenu && (item.getServingStyleId() == null || item.getQuantity() <= 0)) {
                throw new IllegalStateException("Incomplete order item: " + item.getDinnerName());
            }

            if (item.getProductId() != null && !item.getProductId().isEmpty()) {
                // ★ 이미 Product가 있으면 그대로 사용 (추가 메뉴 포함)
                Product existingProduct = productRepository.findById(UUID.fromString(item.getProductId()))
                        .orElseThrow(() -> new IllegalStateException("Product not found: " + item.getProductId()));
                products.add(existingProduct);
            } else if (!isAdditionalMenu) {
                // ★ 디너 Product만 새로 생성 (추가 메뉴는 이미 productId가 있음)
                for (int i = 0; i < item.getQuantity(); i++) {
                    ProductResponseDto productDto = createProductForVoiceOrder(
                            item.getDinnerId(), item.getServingStyleId(), 1, deliveryAddress);
                    Product product = productRepository.findById(UUID.fromString(productDto.getId()))
                            .orElseThrow(() -> new IllegalStateException("Created product not found"));
                    products.add(product);
                }
            }
        }

        Cart cart = Cart.builder()
                .user(user)
                .deliveryAddress(deliveryAddress)
                .deliveryMethod(DeliveryMethod.Delivery)
                .memo("")
                .discountAmount(BigDecimal.ZERO)
                .deliveryFee(BigDecimal.ZERO)
                .status(CartStatus.OPEN)
                .build();

        // ★ OrderItemDto에서 커스터마이징된 가격 사용
        BigDecimal subtotal = BigDecimal.ZERO;
        for (int i = 0; i < products.size() && i < orderItems.size(); i++) {
            Product product = products.get(i);
            OrderItemDto orderItem = orderItems.get(i);

            // ★ 커스터마이징 반영된 가격 사용 (OrderItemDto.getTotalPrice)
            BigDecimal unitPrice = BigDecimal.valueOf(orderItem.getTotalPrice());

            cart.getProducts().add(product);
            cart.getProductQuantities().put(product.getId(), 1);
            cart.getProductUnitPrices().put(product.getId(), unitPrice);
            subtotal = subtotal.add(unitPrice);
        }

        cart.setSubtotal(subtotal);
        cart.setGrandTotal(subtotal);
        Cart savedCart = cartRepository.save(cart);

        Order order = orderService.createOrderFromCart(savedCart);
        savedCart.setStatus(CartStatus.CHECKED_OUT);
        cartRepository.save(savedCart);

        Order savedOrder = orderService.saveOrder(order);
        return OrderResponseDto.from(savedOrder);
    }

    // ========================================
    // DTO 빌더
    // ========================================

    private StoreUpdateDto.StyleSetDto buildStyleSetDto(DinnerResponseDto dinner, ServingStyleResponseDto style,
            ProductResponseDto product, int instanceIndex) {
        List<StoreUpdateDto.ProductMenuItemDto> menuItems = new ArrayList<>();
        if (product.getProductMenuItems() != null) {
            for (var pmi : product.getProductMenuItems()) {
                menuItems.add(StoreUpdateDto.ProductMenuItemDto.builder()
                        .menuItemId(pmi.getMenuItemId())
                        .menuItemName(pmi.getMenuItemName())
                        .defaultQuantity(pmi.getQuantity())
                        .currentQuantity(pmi.getQuantity())
                        .unitPrice(pmi.getUnitPrice())
                        .build());
            }
        }

        return StoreUpdateDto.StyleSetDto.builder()
                .dinnerId(dinner.getId().toString())
                .dinnerName(dinner.getDinnerName())
                .instanceIndex(instanceIndex)
                .styleId(style.getId().toString())
                .styleName(style.getStyleName())
                .styleExtraPrice(style.getExtraPrice())
                .productId(product.getId())
                .productName(product.getProductName())
                .totalPrice(product.getTotalPrice())
                .productMenuItems(menuItems)
                .build();
    }

    // ========================================
    // 유틸리티 메서드들
    // ========================================

    private String extractUserMessage(ChatRequestDto request) {
        if (request.getAudioBase64() != null && !request.getAudioBase64().isEmpty()) {
            byte[] audioData = Base64.getDecoder().decode(request.getAudioBase64());
            return groqService.transcribe(audioData, request.getAudioFormat());
        }
        return request.getMessage();
    }

    private List<Map<String, String>> convertHistory(List<ChatMessageDto> history) {
        List<Map<String, String>> result = new ArrayList<>();
        if (history != null) {
            for (ChatMessageDto msg : history) {
                result.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
            }
        }
        return result;
    }

    private String formatAddressList(List<String> addresses) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < addresses.size(); i++) {
            sb.append(String.format("%d. %s\n", i + 1, addresses.get(i)));
        }
        return sb.toString().trim();
    }

    private String buildOrderSummary(List<OrderItemDto> orderItems, String address) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (OrderItemDto item : orderItems) {
            String koreanDinnerName = menuMatcher.toKoreanDinnerName(item.getDinnerName());
            String koreanStyleName = item.getServingStyleName() != null
                    ? menuMatcher.toKoreanStyleName(item.getServingStyleName())
                    : "스타일 미선택";
            sb.append(String.format("• %s (%s) x%d = %,d원\n",
                    koreanDinnerName, koreanStyleName, item.getQuantity(), item.getTotalPrice()));

            if (item.getMenuItems() != null && !item.getMenuItems().isEmpty()) {
                for (var mi : item.getMenuItems()) {
                    int diff = mi.getCurrentQuantity() - mi.getDefaultQuantity();
                    if (diff != 0) {
                        String changeStr = diff > 0 ? "+" + diff : String.valueOf(diff);
                        int priceDiff = mi.getPriceDiff();
                        String priceStr = priceDiff != 0 ?
                                String.format(" (%s원)", priceDiff > 0 ? "+" + priceDiff : String.valueOf(priceDiff)) : "";
                        sb.append(String.format("  └ %s %s%s\n", mi.getMenuItemName(), changeStr, priceStr));
                    }
                }
            }
            total += item.getTotalPrice();
        }
        sb.append(String.format("\n총 금액: %,d원", total));
        if (address != null && !address.isEmpty()) {
            sb.append(String.format("\n배달 주소: %s", address));
        }
        return sb.toString();
    }

    private UserIntent parseIntent(String intentStr) {
        if (intentStr == null) return UserIntent.UNKNOWN;
        try {
            return UserIntent.valueOf(intentStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UserIntent.UNKNOWN;
        }
    }

    private OrderItemDto findPendingItem(List<OrderItemDto> orderItems) {
        for (OrderItemDto item : orderItems) {
            if (item.getServingStyleId() == null) {
                return item;
            }
        }
        return null;
    }

    private int findOrderItemIndex(List<OrderItemDto> orderItems, String menuName) {
        for (int i = 0; i < orderItems.size(); i++) {
            if (orderItems.get(i).getDinnerName().equalsIgnoreCase(menuName) ||
                    menuMatcher.isMatchingMenu(orderItems.get(i).getDinnerName(), menuName)) {
                return i;
            }
        }
        return -1;
    }

    // ========================================
    // LLM 프롬프트 및 응답 처리
    // ========================================

    private String buildSystemPrompt(List<OrderItemRequestDto> currentOrder, String selectedAddress, List<String> userAddresses) {
        StringBuilder orderSummary = new StringBuilder();
        if (currentOrder != null && !currentOrder.isEmpty()) {
            orderSummary.append("\n\n## Current Order\n");
            for (OrderItemRequestDto item : currentOrder) {
                orderSummary.append(String.format("- %s (%s) x%d = %,d원\n",
                        item.getDinnerName(),
                        item.getServingStyleName() != null ? item.getServingStyleName() : "스타일 미선택",
                        item.getQuantity(), item.getTotalPrice()));
            }
        }

        StringBuilder addressInfo = new StringBuilder();
        if (selectedAddress != null && !selectedAddress.isEmpty()) {
            addressInfo.append("\n\n## Selected Address: ").append(selectedAddress);
        }
        if (userAddresses != null && !userAddresses.isEmpty()) {
            addressInfo.append("\n\n## User's Addresses:\n");
            for (int i = 0; i < userAddresses.size(); i++) {
                addressInfo.append(String.format("%d. %s\n", i + 1, userAddresses.get(i)));
            }
        }

        return String.format("""
                You are an AI order assistant for "Mr.Daeback" premium dinner delivery.
                ⚠️ CRITICAL: Respond with ONLY a single JSON object. NO other text!

                ## Dinners
                %s

                ## Styles
                %s
                %s%s

                ## Flow: Address → Dinner → Style → Customize(optional) → Checkout

                ## Output Format
                {"intent":"INTENT","entities":{"menuName":null,"styleName":null,"quantity":null,"addressIndex":null,"menuItemName":null,"action":null,"menuItemQuantity":null,"specialRequest":null},"message":"한글 응답"}

                ## Intents
                - GREETING, SELECT_ADDRESS, ORDER_MENU, SELECT_STYLE, SET_QUANTITY
                - ADD_TO_CART, CUSTOMIZE_MENU, ADD_ADDITIONAL_MENU, SET_MEMO
                - SKIP_CUSTOMIZE, CONFIRM_ORDER, ASK_MENU_INFO

                ## CUSTOMIZE_MENU Action Codes (IMPORTANT!)
                For CUSTOMIZE_MENU intent, action MUST be one of these standardized codes:
                - "ADD": Increase quantity (e.g., "스테이크 2개 추가" → action:"ADD", menuItemQuantity:2)
                - "REMOVE": Decrease quantity (e.g., "스테이크 빼줘" → action:"REMOVE", menuItemQuantity:1)
                - "SET": Set exact quantity (e.g., "스테이크 0개로" → action:"SET", menuItemQuantity:0)

                ## Rules
                1. JSON only!
                2. menuName/styleName in English: "Valentine Dinner", "Simple Style" etc.
                3. Champagne Feast dinner: No Simple Style!
                4. CUSTOMIZE_MENU action must be exactly "ADD", "REMOVE", or "SET" (uppercase English)
                """,
                menuMatcher.getMenuListForPrompt(),
                menuMatcher.getStyleListForPrompt(),
                orderSummary.toString(),
                addressInfo.toString()
        );
    }

    private LlmResponseDto parseLlmResponse(String rawResponse) {
        try {
            String jsonContent = extractJsonFromResponse(rawResponse);
            return objectMapper.readValue(jsonContent, LlmResponseDto.class);
        } catch (JsonProcessingException e) {
            log.warn("[LLM] JSON 파싱 실패: {}", e.getMessage());
            return buildFallbackResponse(rawResponse);
        }
    }

    private String extractJsonFromResponse(String rawResponse) {
        String jsonContent = rawResponse.trim();

        // ```json ... ``` 형식 처리
        if (jsonContent.contains("```json")) {
            int start = jsonContent.indexOf("```json") + 7;
            int end = jsonContent.indexOf("```", start);
            if (end > start) {
                jsonContent = jsonContent.substring(start, end).trim();
            }
        } else if (jsonContent.contains("```")) {
            int start = jsonContent.indexOf("```") + 3;
            int end = jsonContent.indexOf("```", start);
            if (end > start) {
                jsonContent = jsonContent.substring(start, end).trim();
            }
        }

        // JSON 추출
        int lastBraceStart = jsonContent.lastIndexOf("{\"intent\"");
        if (lastBraceStart == -1) {
            lastBraceStart = jsonContent.lastIndexOf("{");
        }
        if (lastBraceStart > 0) {
            String potentialJson = jsonContent.substring(lastBraceStart);
            int braceCount = 0;
            int jsonEnd = -1;
            for (int i = 0; i < potentialJson.length(); i++) {
                char c = potentialJson.charAt(i);
                if (c == '{') braceCount++;
                else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        jsonEnd = i + 1;
                        break;
                    }
                }
            }
            if (jsonEnd > 0) {
                jsonContent = potentialJson.substring(0, jsonEnd);
            }
        }

        return jsonContent.trim();
    }

    private LlmResponseDto buildFallbackResponse(String rawResponse) {
        LlmResponseDto fallback = new LlmResponseDto();

        String extractedIntent = extractFieldFromJson(rawResponse, "intent");
        fallback.setIntent(extractedIntent != null ? extractedIntent : "ASK_MENU_INFO");

        LlmResponseDto.ExtractedEntities entities = new LlmResponseDto.ExtractedEntities();
        String styleName = extractFieldFromJson(rawResponse, "styleName");
        if (styleName != null && !styleName.equals("null")) {
            entities.setStyleName(styleName);
        }
        String menuName = extractFieldFromJson(rawResponse, "menuName");
        if (menuName != null && !menuName.equals("null")) {
            entities.setMenuName(menuName);
        }
        String quantityStr = extractFieldFromJson(rawResponse, "quantity");
        if (quantityStr != null && !quantityStr.equals("null")) {
            try {
                entities.setQuantity(Integer.parseInt(quantityStr));
            } catch (NumberFormatException ignored) {}
        }
        fallback.setEntities(entities);

        String message = extractFieldFromJson(rawResponse, "message");
        fallback.setMessage(message != null ? message.replaceAll("[\\u2028\\u2029]+.*", "").trim()
                : "처리 중 오류가 발생했습니다. 다시 말씀해주세요.");

        return fallback;
    }

    private String extractFieldFromJson(String json, String fieldName) {
        if (json == null || fieldName == null) return null;

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\"" + fieldName + "\"\\s*:\\s*\"?([^\"\\},]+)\"?"
        );
        java.util.regex.Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {
            String value = matcher.group(1).trim();
            if (value.equals("null") || value.isEmpty()) {
                return null;
            }
            return value;
        }
        return null;
    }

    // ========================================
    // 컨텍스트 클래스
    // ========================================

    private static class IntentContext {
        LlmResponseDto.ExtractedEntities entities;
        List<OrderItemDto> updatedOrder;
        String selectedAddress;
        List<String> userAddresses;
        UUID userId;
        String message;
        OrderFlowState nextState;
        UiAction uiAction;
        StoreUpdateDto.StoreUpdateDtoBuilder storeUpdateBuilder;
        OrderItemDto pendingItem;
        int pendingIdx;
        String orderId;
        String orderNumber;
        String specialRequest;
    }
}
