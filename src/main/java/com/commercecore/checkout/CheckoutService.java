package com.commercecore.checkout;

import com.commercecore.cart.CartItem;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.Product;
import com.commercecore.catalog.ProductRepository;
import com.commercecore.outbox.AggregateType;
import com.commercecore.outbox.OutboxEventType;
import com.commercecore.outbox.OutboxEventWriter;
import com.commercecore.reservation.ReservationService;
import com.commercecore.shared.BusinessRuleViolation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts cart intent into inventory ownership: reads current cart lines, snapshots current
 * catalog prices, reserves stock for every line, and creates a persistent PENDING order — all
 * inside one database transaction, so checkout either fully succeeds or leaves no trace at all.
 *
 * <p>Every checkout requires an {@code Idempotency-Key}; see
 * {@link #checkoutIdempotently(UUID, String)} and {@code docs/idempotency.md} for how retries
 * are made safe.
 */
@Service
public class CheckoutService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final CartService cartService;
    private final ProductRepository productRepository;
    private final ReservationService reservationService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CheckoutIdempotencyRepository idempotencyRepository;
    private final OutboxEventWriter outboxEventWriter;

    public CheckoutService(CartService cartService, ProductRepository productRepository,
        ReservationService reservationService, OrderRepository orderRepository,
        OrderItemRepository orderItemRepository, CheckoutIdempotencyRepository idempotencyRepository,
        OutboxEventWriter outboxEventWriter) {
        this.cartService = cartService;
        this.productRepository = productRepository;
        this.reservationService = reservationService;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.outboxEventWriter = outboxEventWriter;
    }

    private record Line(String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    /**
     * The sole transactional entry point into checkout. Validates the key, acquires the
     * per-key advisory lock (see {@link CheckoutIdempotencyRepository#acquireLock}), and only
     * then decides whether this is a first execution or a replay:
     *
     * <ul>
     *   <li>No mapping for this key yet: run {@link #executeCheckout}, insert the mapping row,
     *       return {@code created = true}. If {@code executeCheckout} throws (e.g. insufficient
     *       stock), this whole {@code @Transactional} method throws too — the transaction rolls
     *       back, so no order, no reservations, and critically no idempotency mapping survive.
     *       The key remains completely unused and safe to retry.
     *   <li>A mapping already exists for this key with the <em>same</em> cart: the original
     *       checkout already happened. Return its order, {@code created = false}. Checkout is
     *       not re-run — no re-validation of stock, no re-pricing, regardless of what has
     *       changed about the cart or catalog since.
     *   <li>A mapping already exists for this key with a <em>different</em> cart: the key is
     *       being reused for a different logical request, which is rejected outright
     *       ({@code idempotency_key_reused}) rather than silently returning someone else's
     *       order.
     * </ul>
     *
     * <p>{@code executeCheckout} is deliberately a private, non-{@code @Transactional} method
     * called directly (self-invocation) from here: it relies entirely on the transaction this
     * method's own Spring proxy already opened. Giving it its own {@code @Transactional} would
     * be misleading (self-invocation bypasses the proxy, so that annotation would never actually
     * take effect) and unnecessary (default {@code REQUIRED} propagation would just join this
     * transaction anyway, since one is already active for the whole duration of the call).
     */
    @Transactional
    public CheckoutResult checkoutIdempotently(UUID cartId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "missing_idempotency_key",
                "Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "invalid_idempotency_key",
                "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }

        idempotencyRepository.acquireLock(idempotencyKey);

        Optional<CheckoutIdempotency> existing = idempotencyRepository.findById(idempotencyKey);
        if (existing.isPresent()) {
            CheckoutIdempotency mapping = existing.get();
            if (!mapping.getCartId().equals(cartId)) {
                throw new BusinessRuleViolation(HttpStatus.CONFLICT, "idempotency_key_reused",
                    "Idempotency-Key already used for a different cart");
            }
            return new CheckoutResult(getOrder(mapping.getOrderId()), false);
        }

        Order order = executeCheckout(cartId);
        idempotencyRepository.save(new CheckoutIdempotency(idempotencyKey, cartId, order.getId(), Instant.now()));
        return new CheckoutResult(order, true);
    }

    /**
     * Two passes, deliberately in this order:
     *
     * <p>Pass 1 is read-only — it validates the cart isn't empty, reads each product's current
     * price, and computes line/order totals server-side. Nothing is written yet, so nothing here
     * needs to be undone if a later SKU turns out to be invalid.
     *
     * <p>Pass 2 does the writes. The order row is inserted first, already carrying its final,
     * fully-computed total (no placeholder-then-update needed) — and it must come first, because
     * both {@code order_items.order_id} and the {@code inventory_reservations.order_id}
     * column are foreign keys to {@code orders.id}; inserting either before the order row exists
     * would fail the constraint immediately, not just at commit. The {@code ORDER_CREATED} outbox
     * event is written immediately after, in this same transaction (see
     * {@code docs/outbox.md}) — a committed order with no corresponding event, or an event with
     * no order, are equally impossible outcomes. Cart lines are processed in SKU order so that
     * two concurrent checkouts sharing overlapping SKUs always acquire inventory row locks in the
     * same order, avoiding a lock-ordering deadlock. Each line reuses
     * {@link ReservationService#reserve(String, int, UUID) the existing atomic reservation
     * primitive} — the same conditional-UPDATE mechanism that already proved no-overselling in
     * Milestone 1 — rather than reimplementing stock-claiming here.
     *
     * <p>If any reservation fails (insufficient stock), this method throws and the whole
     * transaction (opened by {@link #checkoutIdempotently}) rolls back: the order row, the
     * {@code ORDER_CREATED} event, any order items already inserted, and any
     * reservations/decrements already made earlier in this same loop are all undone together. No
     * manual compensation is needed because nothing here was ever committed on its own. A
     * replayed checkout (same {@code Idempotency-Key}) never reaches this method a second time,
     * so it never produces a second {@code ORDER_CREATED} event either.
     */
    private Order executeCheckout(UUID cartId) {
        cartService.getCart(cartId);
        List<CartItem> items = cartService.getItems(cartId);
        if (items.isEmpty()) {
            throw new BusinessRuleViolation(HttpStatus.CONFLICT, "empty_cart", "Cart is empty: " + cartId);
        }

        List<CartItem> sortedItems = items.stream()
            .sorted(Comparator.comparing(CartItem::getSku))
            .toList();

        List<Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (CartItem item : sortedItems) {
            Product product = productRepository.findBySku(item.getSku())
                .orElseThrow(() -> new BusinessRuleViolation(HttpStatus.NOT_FOUND, "unknown_sku",
                    "Unknown SKU: " + item.getSku()));
            BigDecimal unitPrice = product.getPriceAmount();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
            total = total.add(lineTotal);
            lines.add(new Line(item.getSku(), item.getQuantity(), unitPrice, lineTotal));
        }

        UUID orderId = UUID.randomUUID();
        Order order = orderRepository.save(new Order(orderId, OrderStatus.PENDING, total, Instant.now()));
        outboxEventWriter.write(AggregateType.ORDER, orderId, OutboxEventType.ORDER_CREATED,
            new OrderCreatedPayload(orderId, OrderStatus.PENDING.name(), total));

        for (Line line : lines) {
            reservationService.reserve(line.sku(), line.quantity(), orderId);
            orderItemRepository.save(
                new OrderItem(orderId, line.sku(), line.quantity(), line.unitPrice(), line.lineTotal()));
        }

        return order;
    }

    public Order getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new BusinessRuleViolation(HttpStatus.NOT_FOUND, "order_not_found",
                "Unknown order: " + orderId));
    }

    public List<OrderItem> getItems(UUID orderId) {
        return orderItemRepository.findByOrderId(orderId);
    }
}
