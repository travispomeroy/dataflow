# Positions grain: latest-order collapse

The Positions Source merges three upstream APIs, and positions↔orders is one-to-many: a
Client can have several orders for the same symbol. The delivered Positions Feed had no
defined row grain, so the merge's output shape was undecided. We decided the feed is
**one row per position**; the order columns show the *latest* order for the
`(Client, symbol)` pair — max `tradeDate`, tie-broken by max `orderId`. Positions with
no orders keep empty order columns; orders with no matching position are dropped. The
collapse is hidden Source composition: it lives in the Catalog Source's physical
definition and is surfaced in the Execution Plan, so every engine compiler generates the
same collapse from the plan alone.

## Considered Options

- **Explode (left-join as-is)** — one row per position × order preserves every order, but
  duplicates `quantity` and `marketValue` across rows. Any downstream summation then
  double-counts: M6's concentration aggregation and any provider-side roll-up would be
  silently wrong. In a financial feed, a value that poisons aggregation is worse than a
  value that is absent.
- **Collapse to the latest order (chosen)** — keeps the position grain safe to sum,
  still exercises the orders API end-to-end (its pagination stays proven by the
  byte-golden e2e), and the deterministic pick (max `tradeDate`, then max `orderId`)
  keeps output byte-comparable.
- **Drop the order columns** — the safest grain, but the orders API would then feed
  nothing observable: its extraction and pagination would be unproven by the delivered
  bytes, and the feed loses order context the provider wants.

## Consequences

- Row grain is a Source-level physical fact, not a File Definition choice: every file a
  Dataflow produces from the Positions Source shares the position grain.
- The Execution Plan's extraction step carries the collapse rule; engine compilers must
  implement it (a deterministic latest-per-key reduction), starting with Hop in M2.
- Orders without positions are invisible in the Positions Feed by design; an
  orders-grain feed is a different Source (M7's concern), not a variant of this one.
- Full order history per position is out of scope for this feed; consumers needing it
  need an orders-grain feed, not a wider Positions Feed.
