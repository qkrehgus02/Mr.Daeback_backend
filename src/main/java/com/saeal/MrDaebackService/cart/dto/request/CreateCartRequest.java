package com.saeal.MrDaebackService.cart.dto.request;

import com.saeal.MrDaebackService.cart.enums.DeliveryMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@NoArgsConstructor
public class CreateCartRequest {

    @NotEmpty
    @Valid
    private List<CartItemRequest> items;

    private String deliveryAddress;

    private DeliveryMethod deliveryMethod;

    private String memo;

    private LocalDateTime expiresAt;

    @Getter
    @NoArgsConstructor
    public static class CartItemRequest {
        @NotNull
        private String productId;

        @NotNull
        @Min(1)
        private Integer quantity;
    }
}
