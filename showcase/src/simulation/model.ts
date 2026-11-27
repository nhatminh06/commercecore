export const products = [
  { sku: 'NOTEBOOK', name: 'Field notebook', cents: 1200, stock: 5 },
  { sku: 'MUG', name: 'Workshop mug', cents: 1800, stock: 3 },
  { sku: 'PEN', name: 'Technical pen', cents: 500, stock: 8 },
] as const;
export type Sku = typeof products[number]['sku'];
type Lines = Record<Sku, number>;
export type Order = {
  id: string; cart: string; key: string; lines: Lines; total: number;
  status: 'PENDING' | 'CONFIRMED' | 'CANCELLED';
  reservation: 'ACTIVE' | 'CONFIRMED' | 'RELEASED' | 'EXPIRED';
  payment: 'NOT_STARTED' | 'AUTHORIZED' | 'FAILED' | 'UNKNOWN';
  review: 'NONE' | 'RESOLVED' | 'REQUIRES_REVIEW';
  authorizations: number; lookups: number;
};
export type State = {
  stock: Lines; carts: Record<'cart-a' | 'cart-b', Lines>; cart: 'cart-a' | 'cart-b';
  orders: Order[]; selected: string | null; message: string; error: boolean;
};
const empty = (): Lines => ({ NOTEBOOK: 0, MUG: 0, PEN: 0 });
export function initialState(): State {
  return { stock: { NOTEBOOK: 5, MUG: 3, PEN: 8 }, carts: { 'cart-a': empty(), 'cart-b': empty() },
    cart: 'cart-a', orders: [], selected: null, message: 'Local browser simulation ready. Add items to a demo cart.', error: false };
}

// Unknown actions are validated here as well as in the controls; rejected actions never mutate input.
export function transition(state: State, action: unknown): State {
  const reject = (message: string): State => ({ ...state, message, error: true });
  const accept = (patch: Partial<State>, message: string): State => ({ ...state, ...patch, message, error: false });
  if (!action || typeof action !== 'object' || !('type' in action)) return reject('Malformed action.');
  const a = action as Record<string, unknown>;
  if (a.type === 'reset') return initialState();
  if (a.type === 'cart') {
    if (a.id !== 'cart-a' && a.id !== 'cart-b') return reject('Unknown demo cart.');
    return accept({ cart: a.id }, `Selected ${a.id}. Existing orders and key mappings are retained.`);
  }
  if (a.type === 'quantity') {
    if (!products.some(p => p.sku === a.sku) || typeof a.quantity !== 'number' || !Number.isInteger(a.quantity) || a.quantity < 0 || a.quantity > 20) return reject('Quantity must be an integer from 0 to 20 for a known product.');
    return accept({ carts: { ...state.carts, [state.cart]: { ...state.carts[state.cart], [a.sku as Sku]: a.quantity } } }, 'Cart updated. Stock is reserved only at checkout.');
  }
  if (a.type === 'checkout') {
    if (typeof a.key !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(a.key)) return reject('Use a key of 1–64 letters, digits, underscores, or hyphens.');
    const previous = state.orders.find(order => order.key === a.key);
    if (previous) {
      if (previous.cart !== state.cart) return reject('Idempotency key already used for a different cart.');
      return accept({ selected: previous.id }, `Replayed ${previous.id}; original order returned, no stock consumed again.`);
    }
    if (state.orders.length >= 20) return reject('Demo limit of 20 orders reached. Reset to start again.');
    const lines = state.carts[state.cart];
    if (!products.some(p => lines[p.sku] > 0)) return reject('Cart is empty.');
    if (products.some(p => lines[p.sku] > state.stock[p.sku])) return reject('Insufficient stock. No inventory, order, reservation, or key mapping changed.');
    const stock = { ...state.stock };
    for (const p of products) stock[p.sku] -= lines[p.sku];
    const order: Order = { id: `SIM-ORDER-${state.orders.length + 1}`, cart: state.cart, key: a.key,
      lines: { ...lines }, total: products.reduce((sum, p) => sum + p.cents * lines[p.sku], 0),
      status: 'PENDING', reservation: 'ACTIVE', payment: 'NOT_STARTED', review: 'NONE', authorizations: 0, lookups: 0 };
    return accept({ stock, orders: [...state.orders, order], selected: order.id }, `Created ${order.id}. All lines reserved together in local simulated state.`);
  }
  if (a.type === 'selectOrder') {
    if (!state.orders.some(order => order.id === a.id)) return reject('Unknown simulated order.');
    return accept({ selected: a.id as string }, `Selected ${a.id}.`);
  }
  const order = state.orders.find(order => order.id === state.selected);
  if (!order) return reject('Checkout before taking a payment or workflow action.');
  const updated = { ...order };
  let stock = state.stock;
  let message: string;
  switch (a.type) {
    case 'pay':
      if (!['authorized', 'declined', 'lost'].includes(a.outcome as string)) return reject('Unknown payment scenario.');
      if (order.payment !== 'NOT_STARTED') return reject('Authorization already attempted. UNKNOWN requires lookup, not another authorization.');
      updated.authorizations = 1;
      updated.payment = a.outcome === 'authorized' ? 'AUTHORIZED' : a.outcome === 'declined' ? 'FAILED' : 'UNKNOWN';
      message = a.outcome === 'lost' ? 'Response lost after fixed example provider authorization. Local payment UNKNOWN; order remains PENDING.' : `Local payment ${updated.payment}. Apply the separate simulated Kafka workflow next.`;
      break;
    case 'reconcile':
      if (order.payment !== 'UNKNOWN') return reject('Lookup is available only for UNKNOWN in this bounded example.');
      updated.lookups = 1;
      updated.payment = 'AUTHORIZED';
      updated.review = order.reservation === 'ACTIVE' ? 'RESOLVED' : 'REQUIRES_REVIEW';
      message = updated.review === 'REQUIRES_REVIEW' ? 'Lookup found AUTHORIZED but stock ownership was lost. REQUIRES_REVIEW; no order confirmation, re-reservation, or refund.' : 'Explicit lookup found the fixed AUTHORIZED provider outcome. No second authorization. Apply the simulated Kafka workflow separately.';
      break;
    case 'expire':
      if (order.payment !== 'UNKNOWN' || order.reservation !== 'ACTIVE') return reject('Expiry illustration requires UNKNOWN with ACTIVE reservations.');
      updated.reservation = 'EXPIRED';
      stock = { ...state.stock };
      for (const p of products) stock[p.sku] += order.lines[p.sku];
      message = 'Simulated explicit expiry returned reserved stock. A later authorization lookup must require review.';
      break;
    case 'workflow':
      if (!['AUTHORIZED', 'FAILED'].includes(order.payment)) return reject('Workflow needs a definitive payment observation.');
      if (order.status !== 'PENDING') return accept({}, 'Workflow already applied; no duplicate inventory effect.');
      if (order.payment === 'AUTHORIZED') {
        if (order.reservation !== 'ACTIVE') {
          updated.review = 'REQUIRES_REVIEW';
          message = 'Stock ownership is lost. REQUIRES_REVIEW; order remains PENDING.';
        } else {
          updated.status = 'CONFIRMED'; updated.reservation = 'CONFIRMED';
          message = 'Simulated workflow confirmed the order and reservations. No second decrement.';
        }
      } else {
        updated.status = 'CANCELLED'; updated.reservation = 'RELEASED';
        stock = { ...state.stock };
        for (const p of products) stock[p.sku] += order.lines[p.sku];
        message = 'Simulated failure workflow cancelled the order and released stock once.';
      }
      break;
    default: return reject('Malformed or unsupported action.');
  }
  return accept({ stock, orders: state.orders.map(o => o.id === order.id ? updated : o) }, message);
}
