package com.commercecore.catalog;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreateProductRequest(
    @NotBlank String sku,
    @NotBlank String name,
    @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal price,
    @Min(0) int initialQuantity
) {
}
