# Fixtures: the canonical mock-world dataset

`generate.ts` deterministically produces the committed dataset in `data/` — the single
source of truth for what the three mock upstream APIs contain. M1's catalog seed projects
from the field contract below; M0.4's WireMock stubs serve pages of exactly these records.

## Regenerating

```sh
node infra/fixtures/generate.ts
```

Zero dependencies; runs on Node's built-in type stripping (Node pinned in `.nvmrc`).
Reruns are byte-identical forever: fixed seed, seeded PRNG (mulberry32), no wall-clock
reads, JSON keys sorted, 2-space indent, LF endings, trailing newline, fixed file emission
order (`investors.json`, `positions.json`, `orders.json`). The M0 gate deletes `data/`,
reruns the generator, and requires a clean `git status` over `infra/fixtures` — hand-edits
to fixture files are gate failures.

> **Freeze warning** (spec #1): once M2's golden CSVs exist these fixtures are frozen.
> Any later change is a golden-invalidation event requiring explicit justification. That
> is why every M6 edge case is baked in now.

## Vocabulary

The upstream APIs speak **"investor"**; the platform renames to **Client** at the Source
boundary (see `CONTEXT.md`). This dataset is upstream vocabulary — `investorId` everywhere.
`investorId` is the merge key shared by all three files; `(investorId, symbol)` is the
position↔order overlap pair the Positions Source merge is defined on.

## Field contract

### `investors.json` — 10 records

| Field | Type | Contract |
|---|---|---|
| `investorId` | string | `INV-NNN`, unique, the cross-file merge key |
| `name` | string | Display name, ASCII |
| `advisorGroup` | string | One of exactly 4 groups: `Cascade Capital Partners`, `Harbor Point Advisors`, `Meridian Wealth`, `XYZ` |

### `positions.json` — ~200 records

At most one position per `(investorId, symbol)` pair.

| Field | Type | Contract |
|---|---|---|
| `positionId` | string | `POS-NNNN`, unique, ascending in file order |
| `investorId` | string | FK → `investors.json` |
| `symbol` | string | Instrument identifier; cash symbols are `USD-CASH` / `EUR-CASH` / `GBP-CASH` |
| `assetClass` | string | One of exactly 5 tokens: `cash`, `derivatives`, `equity`, `fixed_income`, `other` (the Positions Feed's one-file-per-class split) |
| `quantity` | number | ≥ 0. Equities/derivatives integer; `fixed_income` face value (multiple of 1000); `other` fractional (2dp); `cash` equals `marketValue` |
| `marketValue` | number | ≥ 0, up to 3 decimal places |
| `currency` | string | `USD`, `EUR`, or `GBP`; for cash positions it matches the symbol |

### `orders.json` — ~300 records

| Field | Type | Contract |
|---|---|---|
| `orderId` | string | `ORD-NNNN`, unique, ascending in file order |
| `investorId` | string | FK → `investors.json` |
| `symbol` | string | Same instrument universe as positions, never cash symbols |
| `side` | string | `BUY` or `SELL` |
| `quantity` | number | Positive integer; `fixed_income` orders in multiples of 1000 |
| `status` | string | One of `NEW`, `PARTIALLY_FILLED`, `FILLED`, `CANCELLED` |
| `tradeDate` | string | ISO date, a business day in the fixed window 2026-07-06 … 2026-07-17 |

## Baked-in edge cases (downstream targets)

The generator self-asserts all of these and refuses to emit a dataset that lacks any:

- **Advisor Group `XYZ`** — the M6 negate-rule target; 2 of the 10 investors belong to it.
- **All 5 Asset Classes held by several investors** — `INV-001`…`INV-005` hold all five,
  so the M2 five-file split always produces five non-trivial files.
- **Zero-quantity positions** (`quantity: 0`, `marketValue: 0`) — M6 suppress targets.
- **Awkward-decimal market values** — 3dp values, many ending in an ambiguous trailing 5
  (e.g. `3750.955`) — M6 rounding targets.
- **Merge orphans, both directions**: cash positions never have orders and ~10% of
  non-cash holdings are deliberately order-free (positions without orders); every investor
  also has 1–2 orders on symbols they do not hold (orders without positions). Most orders
  still overlap a position on `(investorId, symbol)`.
