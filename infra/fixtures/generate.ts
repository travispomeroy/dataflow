// Deterministic fixture generator for the mock upstream world (M0.3, issue #4).
// Emits the canonical committed dataset under infra/fixtures/data/. Zero
// dependencies; runs on Node's built-in type stripping:
//
//   node infra/fixtures/generate.ts
//
// Determinism contract (enforced by the regen gate in e2e/m0-gates.sh):
// fixed seed, no wall-clock reads, sorted JSON keys, LF line endings, trailing
// newline, fixed file emission order. Field contract: README.md beside this file.
//
// Fixtures freeze once M2's golden CSVs exist — every downstream edge case
// (Advisor Group "XYZ", zero-quantity positions, awkward decimals, merge
// orphans, all five Asset Classes) is baked in here, now.
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const SEED = 0x0da7af10;
const OUT_DIR = join(import.meta.dirname, "data");

// -- Seeded PRNG (mulberry32) -------------------------------------------------

function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const rng = mulberry32(SEED);

function randInt(min: number, max: number): number {
  return min + Math.floor(rng() * (max - min + 1));
}

function pick<T>(items: readonly T[]): T {
  return items[Math.floor(rng() * items.length)];
}

function pickDistinct<T>(items: readonly T[], count: number): T[] {
  const pool = [...items];
  for (let i = pool.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [pool[i], pool[j]] = [pool[j], pool[i]];
  }
  return pool.slice(0, count).sort();
}

// -- The world ----------------------------------------------------------------

interface Investor {
  investorId: string;
  name: string;
  advisorGroup: string;
}

interface Position {
  positionId: string;
  investorId: string;
  symbol: string;
  assetClass: string;
  quantity: number;
  marketValue: number;
  currency: string;
}

interface Order {
  orderId: string;
  investorId: string;
  symbol: string;
  side: string;
  quantity: number;
  status: string;
  tradeDate: string;
}

// Upstream vocabulary: the APIs say "investor"; the platform renames to Client
// at the Source boundary (CONTEXT.md). "XYZ" is the M6 negate-rule target.
const INVESTORS: Investor[] = [
  { investorId: "INV-001", name: "Alice Thornton", advisorGroup: "Meridian Wealth" },
  { investorId: "INV-002", name: "Benjamin Okafor", advisorGroup: "Harbor Point Advisors" },
  { investorId: "INV-003", name: "Carmen Delgado", advisorGroup: "XYZ" },
  { investorId: "INV-004", name: "Dmitri Volkov", advisorGroup: "Cascade Capital Partners" },
  { investorId: "INV-005", name: "Evelyn Marsh", advisorGroup: "Meridian Wealth" },
  { investorId: "INV-006", name: "Farid Nassar", advisorGroup: "Harbor Point Advisors" },
  { investorId: "INV-007", name: "Grace Liu", advisorGroup: "XYZ" },
  { investorId: "INV-008", name: "Henrik Sorensen", advisorGroup: "Cascade Capital Partners" },
  { investorId: "INV-009", name: "Isabelle Fontaine", advisorGroup: "Meridian Wealth" },
  { investorId: "INV-010", name: "Jack Whitfield", advisorGroup: "Harbor Point Advisors" },
];

const SYMBOLS: Record<string, readonly string[]> = {
  cash: ["EUR-CASH", "GBP-CASH", "USD-CASH"],
  derivatives: [
    "AAPL-C-260-DEC26",
    "ESU26-FUT",
    "MSFT-P-480-SEP26",
    "NVDA-C-220-MAR27",
    "SPX-C-6500-DEC26",
    "TSLA-P-300-JAN27",
    "WTI-CL-AUG26-FUT",
  ],
  equity: [
    "AAPL", "AMZN", "BRK-B", "GOOGL", "JNJ", "JPM",
    "MSFT", "NVDA", "PG", "TSLA", "UNH", "XOM",
  ],
  fixed_income: [
    "CORP-GE-2031", "CORP-IBM-2029", "CORP-MSFT-2033", "MUNI-CA-2036",
    "MUNI-NY-2033", "T-2027-11", "T-2030-05", "T-2035-08",
  ],
  other: ["ART-FUND-III", "GLD-ETF", "PE-FUND-VII", "REIT-VNQ", "TIMBER-LP", "WINE-LP"],
};

const ASSET_CLASSES = Object.keys(SYMBOLS).sort();

// How many symbols an investor holds per asset class: [min, max]
const HOLDING_RANGES: Record<string, readonly [number, number]> = {
  cash: [1, 3],
  derivatives: [3, 6],
  equity: [6, 11],
  fixed_income: [4, 7],
  other: [2, 5],
};

const CASH_CURRENCY: Record<string, string> = {
  "EUR-CASH": "EUR",
  "GBP-CASH": "GBP",
  "USD-CASH": "USD",
};

// Weighted mostly-USD; a few EUR/GBP so currency is not constant
const NON_CASH_CURRENCIES = ["USD", "USD", "USD", "USD", "USD", "USD", "USD", "USD", "EUR", "GBP"];

// Fixed business-day window (no wall-clock reads)
const TRADE_DATES = [
  "2026-07-06", "2026-07-07", "2026-07-08", "2026-07-09", "2026-07-10",
  "2026-07-13", "2026-07-14", "2026-07-15", "2026-07-16", "2026-07-17",
];

const SIDES = ["BUY", "SELL"];
// Weighted: mostly FILLED
const STATUSES = [
  "FILLED", "FILLED", "FILLED", "FILLED", "FILLED", "FILLED",
  "NEW", "NEW", "PARTIALLY_FILLED", "CANCELLED",
];

// -- Value generation ---------------------------------------------------------

// Awkward decimals are deliberate M6 rounding targets: some values carry three
// decimal places, some end in an ambiguous trailing 5 (e.g. 1234.005). Scaled
// integers keep every value exactly representable in its shortest JSON form.
function marketValue(): { value: number; awkward: boolean } {
  const roll = rng();
  if (roll < 0.15) return { value: (randInt(1_000, 4_000_000) * 10 + 5) / 1000, awkward: true };
  if (roll < 0.25) return { value: randInt(100_000, 40_000_000) / 1000, awkward: true };
  return { value: randInt(10_000, 60_000_000) / 100, awkward: false };
}

function positionQuantity(assetClass: string): number {
  if (assetClass === "equity") return randInt(5, 5000);
  if (assetClass === "fixed_income") return randInt(1, 500) * 1000;
  if (assetClass === "derivatives") return randInt(1, 250);
  return randInt(1_000, 2_000_000) / 100; // other: fractional units, 2dp
}

// -- Generate -----------------------------------------------------------------

const positions: Position[] = [];
const orders: Order[] = [];
let awkwardCount = 0;
let zeroQuantityCount = 0;

// INV-001..INV-005 hold all five Asset Classes (spec: "several investors");
// the rest hold a random subset of three or four.
for (const investor of INVESTORS) {
  const allFive = Number(investor.investorId.slice(4)) <= 5;
  const classes = allFive ? ASSET_CLASSES : pickDistinct(ASSET_CLASSES, randInt(3, 4));
  const heldSymbols = new Set<string>();

  for (const assetClass of classes) {
    const [min, max] = HOLDING_RANGES[assetClass];
    for (const symbol of pickDistinct(SYMBOLS[assetClass], randInt(min, max))) {
      heldSymbols.add(symbol);

      const zeroQuantity = rng() < 0.05;
      if (zeroQuantity) zeroQuantityCount++;
      const mv = assetClass === "cash"
        ? { value: randInt(100_000, 25_000_000) / 100, awkward: false }
        : marketValue();
      if (mv.awkward && !zeroQuantity) awkwardCount++;
      positions.push({
        positionId: `POS-${String(positions.length + 1).padStart(4, "0")}`,
        investorId: investor.investorId,
        symbol,
        assetClass,
        quantity: zeroQuantity ? 0 : assetClass === "cash" ? mv.value : positionQuantity(assetClass),
        marketValue: zeroQuantity ? 0 : mv.value,
        currency: assetClass === "cash" ? CASH_CURRENCY[symbol] : pick(NON_CASH_CURRENCIES),
      });

      // Orders mostly overlap positions on (investorId, symbol). Cash never
      // trades, and ~10% of non-cash holdings are deliberately order-free —
      // both are the position-without-orders merge orphans.
      if (assetClass === "cash" || rng() < 0.1) continue;
      const orderCount = pick([0, 1, 1, 1, 1, 2, 2, 2, 3, 3]);
      for (let i = 0; i < orderCount; i++) {
        orders.push({
          orderId: `ORD-${String(orders.length + 1).padStart(4, "0")}`,
          investorId: investor.investorId,
          symbol,
          side: pick(SIDES),
          quantity: assetClass === "fixed_income" ? randInt(1, 100) * 1000 : randInt(1, 2000),
          status: pick(STATUSES),
          tradeDate: pick(TRADE_DATES),
        });
      }
    }
  }

  // Orphans the other direction: orders on symbols this investor does not hold
  const notHeld = ASSET_CLASSES.filter((c) => c !== "cash")
    .flatMap((c) => SYMBOLS[c].filter((s) => !heldSymbols.has(s)))
    .sort();
  for (const symbol of pickDistinct(notHeld, randInt(1, 2))) {
    orders.push({
      orderId: `ORD-${String(orders.length + 1).padStart(4, "0")}`,
      investorId: investor.investorId,
      symbol,
      side: pick(SIDES),
      quantity: randInt(1, 2000),
      status: pick(STATUSES),
      tradeDate: pick(TRADE_DATES),
    });
  }
}

// -- Self-check: every spec invariant, loudly ---------------------------------

function ensure(condition: boolean, message: string): void {
  if (!condition) throw new Error(`fixture invariant violated: ${message}`);
}

const advisorGroups = new Set(INVESTORS.map((i) => i.advisorGroup));
ensure(INVESTORS.length === 10, "exactly 10 investors");
ensure(advisorGroups.size === 4 && advisorGroups.has("XYZ"), '4 advisor groups incl. "XYZ"');

const classesByInvestor = new Map<string, Set<string>>();
for (const p of positions) {
  classesByInvestor.set(p.investorId, (classesByInvestor.get(p.investorId) ?? new Set()).add(p.assetClass));
}
const allFiveCount = [...classesByInvestor.values()].filter((s) => s.size === 5).length;
ensure(new Set(positions.map((p) => p.assetClass)).size === 5, "all 5 Asset Classes present");
ensure(allFiveCount >= 3, `several investors hold all 5 Asset Classes (got ${allFiveCount})`);

ensure(positions.length >= 180 && positions.length <= 400, `a few hundred positions (got ${positions.length})`);
ensure(orders.length >= 180 && orders.length <= 400, `a few hundred orders (got ${orders.length})`);
ensure(zeroQuantityCount >= 5, `zero-quantity positions present (got ${zeroQuantityCount})`);
ensure(awkwardCount >= 10, `awkward-decimal market values present (got ${awkwardCount})`);

const positionPairs = new Set(positions.map((p) => `${p.investorId}|${p.symbol}`));
const orderPairs = new Set(orders.map((o) => `${o.investorId}|${o.symbol}`));
const orphanOrderPairs = [...orderPairs].filter((k) => !positionPairs.has(k));
const orphanPositionPairs = [...positionPairs].filter((k) => !orderPairs.has(k));
const overlapPairs = [...orderPairs].filter((k) => positionPairs.has(k));
ensure(orphanOrderPairs.length >= 10, `orders with no matching position (got ${orphanOrderPairs.length})`);
ensure(orphanPositionPairs.length >= 10, `positions with no orders (got ${orphanPositionPairs.length})`);
ensure(overlapPairs.length > orphanOrderPairs.length * 3, "orders mostly overlap positions");

// -- Emit (fixed order, sorted keys, LF, trailing newline) --------------------

function sortKeys(_key: string, value: unknown): unknown {
  if (value !== null && typeof value === "object" && !Array.isArray(value)) {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
    );
  }
  return value;
}

mkdirSync(OUT_DIR, { recursive: true });
const files: [string, unknown][] = [
  ["investors.json", INVESTORS],
  ["positions.json", positions],
  ["orders.json", orders],
];
for (const [name, data] of files) {
  writeFileSync(join(OUT_DIR, name), JSON.stringify(data, sortKeys, 2) + "\n");
}

console.log(
  `wrote ${files.map(([n]) => n).join(", ")} to ${OUT_DIR}\n` +
    `investors=${INVESTORS.length} positions=${positions.length} orders=${orders.length} ` +
    `zeroQty=${zeroQuantityCount} awkward=${awkwardCount} ` +
    `orphanOrderPairs=${orphanOrderPairs.length} orphanPositionPairs=${orphanPositionPairs.length}`,
);
