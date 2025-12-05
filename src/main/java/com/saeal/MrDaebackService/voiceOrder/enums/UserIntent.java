package com.saeal.MrDaebackService.voiceOrder.enums;

/**
 * 사용자 발화 의도 분류
 */
public enum UserIntent {
    // === 주문 플로우 관련 ===
    ORDER_MENU,         // 메뉴 주문 ("발렌타인 디너 주세요")
    SET_QUANTITY,       // 수량 설정 ("2개요")
    SELECT_STYLE,       // 서빙 스타일 선택 ("그랜드로 해주세요")
    SELECT_ADDRESS,     // 배달 주소 선택 ("1번 주소로", "집으로")

    // === 장바구니/주문 관리 ===
    ADD_TO_CART,        // 장바구니 담기 / 주문 완료 요청 ("더 없어요", "결제할게요")
    EDIT_ORDER,         // 주문 수정 ("수량 바꿔줘")
    REMOVE_ITEM,        // 개별 아이템 삭제 ("발렌타인 빼줘")
    CANCEL_ORDER,       // 주문 전체 취소 ("전체 취소")

    // === 커스터마이징 ===
    CUSTOMIZE_MENU,     // 커스터마이징 요청 ("메뉴 수정할래요", "커스터마이징")
    UPDATE_MENU_ITEM,   // 메뉴 아이템 수량 변경 ("스테이크 2개로")
    ADD_ADDITIONAL_MENU,// 공통 추가 메뉴 추가 ("와인 추가해줘")
    SET_MEMO,           // 특별 요청사항 설정 ("알러지 있어요")
    SKIP_CUSTOMIZE,     // 커스터마이징 스킵 ("기본으로 할게요", "그냥 진행")

    // === 결제 ===
    CONFIRM_ORDER,      // 최종 결제 확인 ("결제할게요", "주문 확정")
    SELECT_PAYMENT,     // 결제 수단 선택 ("1번 카드로")

    // === 응답 ===
    CONFIRM_YES,        // 긍정 응답 ("네", "좋아요")
    CONFIRM_NO,         // 부정 응답 ("아니요")

    // === 정보 요청 ===
    ASK_MENU_INFO,      // 메뉴 정보 문의 ("발렌타인 디너가 뭐야?")
    ASK_ORDER_STATUS,   // 현재 주문 상태 확인 ("지금 뭐 담겼어?")

    // === 기타 ===
    GREETING,           // 인사 ("안녕하세요")
    UNKNOWN             // 알 수 없음
}
