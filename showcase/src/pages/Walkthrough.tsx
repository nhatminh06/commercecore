import { useReducer, useState } from 'react';
import { PageHeader } from '../components/PageHeader';
import { initialState, products, transition } from '../simulation/model';

const money = (cents: number) => `$${(cents / 100).toFixed(2)}`;

export function Walkthrough() {
  const [state, dispatch] = useReducer(transition, undefined, initialState);
  const [key, setKey] = useState('demo-checkout-1');
  const [scenario, setScenario] = useState('authorized');
  const order = state.orders.find(o => o.id === state.selected);
  return <>
    <PageHeader title="Simulated checkout" description="Local browser simulation · deterministic examples, no backend, database, or real payment." />
    <p className="simulation-label">Educational model only. This JavaScript interaction does not test concurrency or prove absence of overselling.</p>
    <div className="walkthrough-grid">
      <section className="panel" aria-labelledby="cart-title">
        <h2 id="cart-title">1. Demo cart and available stock</h2>
        <label htmlFor="demo-cart">Demo cart</label><select id="demo-cart" value={state.cart} onChange={e => dispatch({ type: 'cart', id: e.target.value })}><option value="cart-a">cart-a</option><option value="cart-b">cart-b</option></select>
        <p className="help">Two independent carts let you try conflicting key reuse. Zero removes a line; quantities are bounded to 20.</p>
        {products.map(p => <div className="product-line" key={p.sku}>
          <div><strong>{p.name}</strong><span className="help">{money(p.cents)} · <code>{p.sku}</code></span><span data-testid={`stock-${p.sku}`}>Available: {state.stock[p.sku]}</span></div>
          <label>Quantity: {p.name}<input type="number" min="0" max="20" step="1" value={state.carts[state.cart][p.sku]} onChange={e => dispatch({ type: 'quantity', sku: p.sku, quantity: e.target.valueAsNumber })} /></label>
        </div>)}
        <p>Cart total: <strong>{money(products.reduce((sum, p) => sum + state.carts[state.cart][p.sku] * p.cents, 0))}</strong></p>
        <form onSubmit={e => { e.preventDefault(); dispatch({ type: 'checkout', key }); }}>
          <label>Idempotency key<input value={key} maxLength={64} onChange={e => setKey(e.target.value)} aria-describedby="key-help" /></label>
          <p className="help" id="key-help">1–64 letters, digits, underscores, or hyphens. Same key + same cart returns the original order, even after cart edits. A rejected checkout leaves its key unused.</p>
          <button type="submit">Checkout / retry same key</button>
        </form>
      </section>
      <section className="panel" aria-labelledby="order-title">
        <h2 id="order-title">2. Simulated order and payment</h2>
        <label htmlFor="selected-order">Simulated order</label><select id="selected-order" value={state.selected ?? ''} onChange={e => dispatch({ type: 'selectOrder', id: e.target.value })} disabled={!state.orders.length}><option value="" disabled>No order yet</option>{state.orders.map(o => <option key={o.id} value={o.id}>{o.id}</option>)}</select>
        {order ? <><dl className="state-list">
          <div><dt>Order</dt><dd>{order.id} · {order.status}</dd></div>
          <div><dt>Reservation</dt><dd>{order.reservation}</dd></div>
          <div><dt>Payment</dt><dd>{order.payment}</dd></div>
          <div><dt>Review</dt><dd>{order.review}</dd></div>
          <div><dt>Snapshot</dt><dd>{order.cart} · {money(order.total)}</dd></div>
          <div><dt>Reserved lines</dt><dd>{products.filter(p => order.lines[p.sku]).map(p => `${p.sku} × ${order.lines[p.sku]}`).join(', ')}</dd></div>
          <div><dt>Model actions</dt><dd>Authorizations: {order.authorizations} · Lookups: {order.lookups}</dd></div>
        </dl>
        <label htmlFor="payment-scenario">Payment scenario</label><select id="payment-scenario" value={scenario} onChange={e => setScenario(e.target.value)}><option value="authorized">Authorization succeeds</option><option value="declined">Authorization is declined</option><option value="lost">Provider response lost / timeout</option></select>
        <div className="actions"><button disabled={order.payment !== 'NOT_STARTED'} onClick={() => dispatch({ type: 'pay', outcome: scenario })}>Simulate authorization</button><button disabled={order.payment !== 'UNKNOWN'} onClick={() => dispatch({ type: 'reconcile' })}>Reconcile by provider lookup</button><button disabled={order.payment !== 'UNKNOWN' || order.reservation !== 'ACTIVE'} onClick={() => dispatch({ type: 'expire' })}>Illustrate reservation expiry</button><button disabled={!['AUTHORIZED', 'FAILED'].includes(order.payment) || order.status !== 'PENDING' || order.review === 'REQUIRES_REVIEW'} onClick={() => dispatch({ type: 'workflow' })}>Apply simulated Kafka workflow</button></div>
        </> : <p>Add an item and check out to create a simulated order.</p>}
        <p className="help">Lost-response example: the provider authorized, but the caller did not observe it. Lookup reads that fixed outcome; it never authorizes again. Workflow application is a separate explicit step here, representing asynchronous processing in the real system.</p>
      </section>
    </div>
    <div className={state.error ? 'feedback error' : 'feedback'} role="status" aria-live="polite" aria-atomic="true">{state.error ? 'Rejected: ' : ''}{state.message}</div>
    <div className="actions"><button onClick={() => { dispatch({ type: 'reset' }); setKey('demo-checkout-1'); setScenario('authorized'); }}>Reset simulation</button><a href="/architecture">Read the real correctness boundaries</a></div>
    <p className="help">Reset, refresh, or leaving this page clears all simulated orders, carts, stock changes, and key mappings. Nothing is stored. The real implementation has no automatic reconciliation scheduler, refunds, or re-reservation policy.</p>
  </>;
}
