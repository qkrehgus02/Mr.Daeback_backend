package com.saeal.MrDaebackService.cart.controller;

import com.saeal.MrDaebackService.cart.dto.request.CreateCartRequest;
import com.saeal.MrDaebackService.cart.dto.response.CartResponseDto;
import com.saeal.MrDaebackService.cart.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/carts")
@Tag(name = "Cart API", description = "Cart 생성 API 입니다.")
public class CartController {

    private final CartService cartService;

    @PostMapping("/createCart")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "장바구니 생성", description = "장바구니를 생성합니다. ")
    public ResponseEntity<CartResponseDto> createCart(
            @Valid @RequestBody CreateCartRequest request
    ) {
        CartResponseDto response = cartService.createCart(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "로그인한 사용자의 장바구니 목록 조회", description = "현재 인증된 사용자에 연결된 모든 카트를 반환합니다.")
    public ResponseEntity<List<CartResponseDto>> getMyCarts() {
        List<CartResponseDto> response = cartService.getCartsForCurrentUser();
        return ResponseEntity.ok(response);
    }
}
