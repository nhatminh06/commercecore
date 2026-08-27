package com.commercecore.payment;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider → CommerceCore, not client → CommerceCore. There is no real payment provider yet, so
 * this endpoint currently accepts payloads shaped for {@link FakePaymentProvider}'s own event
 * model rather than any real processor's webhook format.
 *
 * <p><strong>No signature or authenticity verification is implemented.</strong> A real
 * integration would need to verify the request actually came from the provider (e.g. an HMAC
 * signature header) before trusting its contents — this endpoint does not, and must not be
 * treated as production-secure. See {@code docs/payment-webhooks.md}.
 *
 * <p>Both a first-time accepted delivery and an identical duplicate delivery return
 * {@code 200 OK} with the current payment state — providers commonly retry non-2xx responses, so
 * a validly-processed duplicate must be acknowledged as success, not treated as an error.
 */
@RestController
@RequestMapping("/api/webhooks/payments")
public class PaymentWebhookController {

    private final PaymentWebhookService paymentWebhookService;

    public PaymentWebhookController(PaymentWebhookService paymentWebhookService) {
        this.paymentWebhookService = paymentWebhookService;
    }

    @PostMapping
    public PaymentResponse receive(@RequestBody PaymentWebhookRequest request) {
        Payment payment = paymentWebhookService.processWebhook(request.eventId(), request.providerRequestId(),
            request.type(), request.providerReference());
        return PaymentResponse.from(payment);
    }
}
