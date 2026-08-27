package com.commercecore.shared;

import org.springframework.http.HttpStatus;

/**
 * Expected business rejection (insufficient stock, unknown SKU, duplicate SKU, invalid
 * quantity, ...). Distinct from an unexpected system failure, which should surface as a 500.
 */
public class BusinessRuleViolation extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessRuleViolation(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
