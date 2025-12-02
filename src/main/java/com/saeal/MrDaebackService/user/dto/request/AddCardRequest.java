package com.saeal.MrDaebackService.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class AddCardRequest {

    @NotBlank
    private String cardAlias;

    @NotBlank
    private String cardBrand;

    @NotBlank
    @Size(min = 4, max = 30)
    private String cardNumber;

    @NotNull
    private Integer expiryMonth;

    @NotNull
    private Integer expiryYear;

    private String cardHolderName;

    private Boolean isDefault;
}
