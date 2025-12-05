package com.saeal.MrDaebackService.voiceOrder.enums;

/**
 * 음성 주문 대화 흐름 상태 (GUI 플로우와 동일)
 * GUI 순서: intro → address → dinner → style → customize → checkout
 */
public enum OrderFlowState {
    IDLE,               // 대기 상태 (= intro)
    SELECTING_ADDRESS,  // 1단계: 배달 주소 선택 중
    SELECTING_MENU,     // 2단계: 메뉴(디너) 선택 중
    SELECTING_STYLE,    // 3단계: 서빙 스타일 선택 중
    SELECTING_QUANTITY, // 3-1단계: 수량 선택 중
    ASKING_MORE,        // 3-2단계: 추가 주문 여부 확인
    CUSTOMIZING,        // 4단계: 커스터마이징 중 (메뉴 아이템 수량 변경, 추가 메뉴 추가)
    READY_TO_CHECKOUT,  // 5단계: 결제 준비 완료 (주문 요약 확인)
    CONFIRMING          // 6단계: 최종 확인 및 결제 진행
}
