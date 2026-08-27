package com.commercecore.payment;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development/test API — no authentication. No payment amount is ever accepted from the client;
 * {@code payment.amount} always comes from {@code orders.total_amount}. Every response — success,
 * decline, or the ambiguous UNKNOWN case alike — is {@code 200 OK} with the payment's actual
 * status in the body: the payment's real state is synchronously known and persisted either way,
 * so there's nothing "still processing" for a 202 to represent. See {@code docs/payments.md}.
 */
@RestController
@RequestMapping("/api/orders/{orderId}/payment")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public PaymentResponse initiate(@PathVariable UUID orderId) {
        return PaymentResponse.from(paymentService.initiatePayment(orderId));
    }

    @GetMapping
    public PaymentResponse get(@PathVariable UUID orderId) {
        return PaymentResponse.from(paymentService.getPaymentByOrder(orderId));
    }
}
