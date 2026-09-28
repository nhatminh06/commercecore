package com.commercecore.catalog;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateProductRequest(
    @NotBlank @Size(max = 64) String sku,
    @NotBlank @Size(max = 160) String name,
    @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal price,
    @Min(0) @Max(10_000) int initialQuantity
) {
}
