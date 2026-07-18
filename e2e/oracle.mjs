// Independent oracle for the Positions Feed's delivered CSVs (M2.2, issue #21).
// Computes the five expected files directly from the committed fixtures — a
// second implementation of the feed's semantics, so the Hop engine never
// grades its own homework. Output is committed under e2e/golden/delivered/;
// the M2 gate (issue #27) regenerates and requires a clean diff, mirroring the
// fixture-regen gate.
//
//   node e2e/oracle.mjs
//
// Zero dependencies, deterministic: no wall-clock reads, LF endings, trailing
// newline, fixed file emission order (asset classes ascending). The contract
// implemented here is spec #19 / ADR-0006, stated independently of the
// compiler — a contract change must be made twice to go green:
//   * merge positions + investors + orders on the internal id
//   * one row per position; order columns are the latest order for the
//     (Client, symbol) pair — max tradeDate, tie-break max orderId; positions
//     without orders get empty order columns; orders without positions drop
//   * client filter INV-001/002/003; split by Asset Class into five files
//   * rows sorted (clientId, symbol); header row of plan field names;
//     comma, LF, trailing newline, no enclosure; numerics verbatim
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const FIXTURES_DIR = join(import.meta.dirname, "..", "infra", "fixtures", "data");
const OUT_DIR = join(import.meta.dirname, "golden", "delivered");

// Pinned by spec #19 so delivered names and bytes are fully golden; inside the
// fixtures' trade-date window.
const BUSINESS_DATE = "2026-07-17";

// The canonical Positions Feed's client filter (e2e/canonical/positions-feed.config.json).
const CLIENT_IDS = ["INV-001", "INV-002", "INV-003"];

// The File Definition's column projection, restated here independently of the
// Execution Plan golden. Headers are plan field names verbatim (camelCase).
const COLUMNS = [
  "clientId", "clientName", "advisorGroup", "symbol", "assetClass",
  "quantity", "marketValue", "currency",
  "orderId", "orderSide", "orderQuantity", "orderStatus", "tradeDate",
];

const ASSET_CLASSES = ["cash", "derivatives", "equity", "fixed_income", "other"];

function ensure(condition, message) {
  if (!condition) throw new Error(`oracle invariant violated: ${message}`);
}

// Lines minus header minus the trailing-newline split artifact
function dataRowCount(content) {
  return content.split("\n").length - 2;
}

// -- Load fixtures; prove the parse kept every literal verbatim ---------------
// Delivered numerics must be the upstream literals exactly (3750.955 stays
// 3750.955, 0 stays 0). JSON.parse + String() preserves the fixtures' shortest
// round-trip form; re-serializing with the generator's exact emission rules
// (sorted keys, 2-space indent, trailing newline) and byte-comparing against
// the raw file proves no literal was altered on the way in.
function sortKeys(_key, value) {
  if (value !== null && typeof value === "object" && !Array.isArray(value)) {
    return Object.fromEntries(
      Object.entries(value).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
    );
  }
  return value;
}

function loadVerbatim(name) {
  const raw = readFileSync(join(FIXTURES_DIR, name), "utf8");
  const parsed = JSON.parse(raw);
  ensure(
    JSON.stringify(parsed, sortKeys, 2) + "\n" === raw,
    `${name} does not round-trip byte-identically; verbatim numerics are not guaranteed`,
  );
  return parsed;
}

const investors = loadVerbatim("investors.json");
const positions = loadVerbatim("positions.json");
const orders = loadVerbatim("orders.json");

// -- Merge --------------------------------------------------------------------

const investorById = new Map(investors.map((i) => [i.investorId, i]));

// Latest-order collapse per (investorId, symbol): max tradeDate, tie-break max
// orderId (ADR-0006). Both compare correctly as strings: tradeDate is ISO,
// orderId is fixed-width ORD-NNNN (asserted below).
const latestOrderByPair = new Map();
for (const order of orders) {
  ensure(/^ORD-\d{4}$/.test(order.orderId), `orderId ${order.orderId} is not fixed-width ORD-NNNN`);
  const pair = `${order.investorId}|${order.symbol}`;
  const incumbent = latestOrderByPair.get(pair);
  if (
    !incumbent ||
    order.tradeDate > incumbent.tradeDate ||
    (order.tradeDate === incumbent.tradeDate && order.orderId > incumbent.orderId)
  ) {
    latestOrderByPair.set(pair, order);
  }
}

// One row per position (never per order): orders without positions are simply
// never looked up — dropped by construction.
const filtered = positions.filter((p) => CLIENT_IDS.includes(p.investorId));
const rows = filtered.map((p) => {
  const investor = investorById.get(p.investorId);
  ensure(investor !== undefined, `position ${p.positionId} references unknown investor ${p.investorId}`);
  const order = latestOrderByPair.get(`${p.investorId}|${p.symbol}`);
  return {
    clientId: p.investorId,
    clientName: investor.name,
    advisorGroup: investor.advisorGroup,
    symbol: p.symbol,
    assetClass: p.assetClass,
    quantity: String(p.quantity),
    marketValue: String(p.marketValue),
    currency: p.currency,
    orderId: order ? order.orderId : "",
    orderSide: order ? order.side : "",
    orderQuantity: order ? String(order.quantity) : "",
    orderStatus: order ? order.status : "",
    tradeDate: order ? order.tradeDate : "",
  };
});

// -- Split, sort, format --------------------------------------------------------

// No enclosure is only sound while no value needs enclosing (verified against
// the frozen fixtures; re-asserted on every field here).
function csvLine(values) {
  for (const value of values) {
    ensure(!/[",\r\n]/.test(value), `field ${JSON.stringify(value)} needs enclosure; the no-enclosure contract is broken`);
  }
  return values.join(",");
}

const files = new Map();
for (const assetClass of ASSET_CLASSES) {
  const fileRows = rows
    .filter((r) => r.assetClass === assetClass)
    .sort((a, b) =>
      a.clientId < b.clientId ? -1 : a.clientId > b.clientId ? 1 :
      a.symbol < b.symbol ? -1 : a.symbol > b.symbol ? 1 : 0,
    );
  const lines = [csvLine(COLUMNS), ...fileRows.map((r) => csvLine(COLUMNS.map((c) => r[c])))];
  files.set(`positions_${assetClass}_${BUSINESS_DATE}.csv`, lines.join("\n") + "\n");
}

// -- Self-checks: the contract's observable consequences, loudly ---------------

ensure(new Set(rows.map((r) => r.assetClass)).size === ASSET_CLASSES.length, "every Asset Class present");
for (const [name, content] of files) {
  ensure(dataRowCount(content) >= 1, `${name} is trivial (no data rows)`);
}
ensure(
  rows.length === filtered.length,
  "row grain broken: exactly one row per filtered position (ADR-0006, orphan orders dropped)",
);
ensure(new Set(rows.map((r) => r.clientId)).size === CLIENT_IDS.length, "all three filtered Clients appear");

// Spot-assertions against hand-verified fixture records: full expected lines,
// byte-exact. Each pins one clause of the contract to a concrete row.
const allLines = [...files.values()].flatMap((c) => c.split("\n"));
function spot(description, expectedLine) {
  ensure(allLines.includes(expectedLine), `${description}: expected line missing\n  ${expectedLine}`);
}
// Exact upstream literal market value (the spec's own example, 3750.955)
spot("known position, verbatim market value",
  "INV-001,Alice Thornton,Meridian Wealth,BRK-B,equity,1381,3750.955,USD,ORD-0006,BUY,856,FILLED,2026-07-09");
// Zero-quantity position delivered as literal 0, not 0.0
spot("zero-quantity row present",
  "INV-001,Alice Thornton,Meridian Wealth,TSLA,equity,0,0,USD,ORD-0011,SELL,249,FILLED,2026-07-10");
// Three orders on the pair; ORD-0026 and ORD-0028 share max tradeDate
// 2026-07-13, so the max-orderId tie-break must pick ORD-0028
spot("multi-order pair collapses to latest (max tradeDate, tie-break max orderId)",
  "INV-002,Benjamin Okafor,Harbor Point Advisors,NVDA-C-220-MAR27,derivatives,196,454357.08,USD,ORD-0028,BUY,288,PARTIALLY_FILLED,2026-07-13");
// Cash never trades: orphan position gets empty order columns
spot("orphan position, empty order columns",
  "INV-001,Alice Thornton,Meridian Wealth,USD-CASH,cash,97982.82,97982.82,USD,,,,,");

// -- Emit (fixed order: asset classes ascending) --------------------------------

mkdirSync(OUT_DIR, { recursive: true });
for (const [name, content] of files) {
  writeFileSync(join(OUT_DIR, name), content);
}

console.log(
  `wrote ${files.size} golden CSVs to ${OUT_DIR}\n` +
    [...files.entries()].map(([name, c]) => `${name}: ${dataRowCount(c)} rows`).join("\n"),
);
