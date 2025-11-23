package com.saeal.MrDaebackService.cart.service;

import com.saeal.MrDaebackService.cart.domain.Cart;
import com.saeal.MrDaebackService.cart.enums.CartStatus;
import com.saeal.MrDaebackService.cart.dto.request.CreateCartRequest;
import com.saeal.MrDaebackService.cart.dto.response.CartResponseDto;
import com.saeal.MrDaebackService.cart.repository.CartRepository;
import com.saeal.MrDaebackService.product.domain.Product;
import com.saeal.MrDaebackService.product.repository.ProductRepository;
import com.saeal.MrDaebackService.user.domain.User;
import com.saeal.MrDaebackService.user.repository.UserRepository;
import com.saeal.MrDaebackService.user.service.UserService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final UserService userService;

    @Transactional
    public CartResponseDto createCart(CreateCartRequest request) {
        UUID userId = userService.getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        Cart cart = Cart.builder()
                .user(user)
                .deliveryAddress(request.getDeliveryAddress())
                .deliveryMethod(request.getDeliveryMethod())
                .memo(request.getMemo())
                .expiresAt(request.getExpiresAt())
                .discountAmount(BigDecimal.ZERO)
                .deliveryFee(BigDecimal.ZERO)
                .status(CartStatus.OPEN)
                .build();

        BigDecimal subtotal = BigDecimal.ZERO;

        for (CreateCartRequest.CartItemRequest itemRequest : request.getItems()) {
            UUID productId = UUID.fromString(itemRequest.getProductId());
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

            Integer quantity = itemRequest.getQuantity();
            BigDecimal unitPrice = product.getTotalPrice();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));

            subtotal = subtotal.add(lineTotal);

            cart.getProducts().add(product);
            cart.getProductQuantities().put(product.getId(), quantity);
        }

        cart.setSubtotal(subtotal);
        cart.setGrandTotal(subtotal.add(cart.getDeliveryFee()).subtract(cart.getDiscountAmount()));

        Cart saved = cartRepository.save(cart);
        return CartResponseDto.from(saved);
    }

    @Transactional
    public List<CartResponseDto> getCartsForCurrentUser() {
        UUID userId = userService.getCurrentUserId();
        return cartRepository.findByUserIdAndStatus(userId, CartStatus.OPEN).stream()
                .map(CartResponseDto::from)
                .toList();
    }
}
