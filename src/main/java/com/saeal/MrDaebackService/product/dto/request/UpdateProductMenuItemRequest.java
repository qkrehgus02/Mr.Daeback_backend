package com.saeal.MrDaebackService.product.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class UpdateProductMenuItemRequest {

    @NotNull
    @Min(1)
    private Integer quantity;

    // 단가를 변경하고 싶을 때 사용, null이면 기존 단가 유지
    private BigDecimal unitPrice;
}
