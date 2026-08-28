package com.commercecore.payment;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development-only control surface for {@link PaymentReconciliationService} — not a real
 * production API, and deliberately not a customer-facing endpoint: reconciliation is driven by
 * operational need, never by a client request. No scheduler exists yet (Milestone 11 does not
 * require one); this is how reconciliation is exercised outside of tests until one is added.
 */
@RestController
public class DevReconciliationController {

    private final PaymentReconciliationService reconciliationService;

    public DevReconciliationController(PaymentReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/api/dev/payments/{paymentId}/reconcile")
    public PaymentReconciliationCase reconcileOne(@PathVariable UUID paymentId) {
        return reconciliationService.reconcile(paymentId).orElse(null);
    }

    @PostMapping("/api/dev/reconciliation/run")
    public PaymentReconciliationService.BatchResult run(@RequestParam(defaultValue = "25") int limit) {
        return reconciliationService.reconcileUnknownBatch(limit);
    }
}
