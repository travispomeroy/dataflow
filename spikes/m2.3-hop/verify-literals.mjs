#!/usr/bin/env node
// M2.3 spike (issue #22) — throwaway, NOT production code.
// Q3: prove numeric literals survive Hop to the CSV bytes unchanged.
// Compares out/positions-page-1.csv against the RAW TEXT of the committed
// fixture (infra/fixtures/data/positions.json) — literals are extracted from
// the JSON source with a regex, never round-tripped through a JS number, so
// this check cannot itself launder "100.0" back into "100".
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const fixtureText = readFileSync(
  join(here, "../../infra/fixtures/data/positions.json"),
  "utf8",
);

// The generator emits sorted keys, 2-space indent, one "key": value per line.
// Parse each object block's raw literal text per field.
const objects = [];
const objRe = /\{[^{}]*\}/g;
for (const [block] of fixtureText.matchAll(objRe)) {
  const fields = {};
  for (const [, key, raw] of block.matchAll(/"([A-Za-z]+)": ("[^"]*"|[-0-9.eE+]+)/g)) {
    fields[key] = raw.startsWith('"') ? raw.slice(1, -1) : raw;
  }
  objects.push(fields);
}
if (objects.length !== 207) {
  throw new Error(`expected 207 fixture positions, parsed ${objects.length}`);
}

// Page 1 at pageSize 50 = the first 50 records in file order (the stubs are
// generator-emitted slices of the same file order).
const expected = objects.slice(0, 50);

const csv = readFileSync(join(here, "out/positions-page-1.csv"), "utf8");
const lines = csv.split("\n");
if (lines.at(-1) !== "") throw new Error("CSV must end with a trailing newline");
const header = lines[0];
const want =
  "positionId,investorId,symbol,assetClass,quantity,marketValue,currency";
if (header !== want) throw new Error(`header mismatch:\n got ${header}\nwant ${want}`);
const rows = lines.slice(1, -1);
if (rows.length !== 50) throw new Error(`expected 50 data rows, got ${rows.length}`);

let failures = 0;
const interesting = [];
rows.forEach((row, i) => {
  const e = expected[i];
  const wantRow = [
    e.positionId, e.investorId, e.symbol, e.assetClass,
    e.quantity, e.marketValue, e.currency,
  ].join(",");
  if (row !== wantRow) {
    failures++;
    console.error(`row ${i + 1} MISMATCH\n  got  ${row}\n  want ${wantRow}`);
  }
  // Show the spike's named targets in the evidence output.
  if (
    e.marketValue === "3750.955" ||        // 3dp survives
    e.quantity === "0" ||                  // bare zero survives
    (!e.quantity.includes(".") && !e.marketValue.includes(".")) // int-valued
  ) {
    interesting.push({ row: i + 1, got: row });
  }
});

// The named targets from issue #22 must actually be present in the evidence —
// otherwise a fixture change could hollow this proof out while it keeps passing.
const has3dp = expected.some((e) => e.marketValue === "3750.955");
const hasZero = expected.some((e) => e.quantity === "0");
const hasIntPair = expected.some(
  (e) => !e.quantity.includes(".") && !e.marketValue.includes("."),
);
if (!has3dp || !hasZero || !hasIntPair) {
  console.error(
    `FAIL: page 1 no longer contains the named target literals ` +
      `(3dp=${has3dp}, zero=${hasZero}, integer-valued=${hasIntPair}) — ` +
      `the fixtures changed; this proof needs new targets`,
  );
  process.exit(1);
}

console.log(`${rows.length} rows byte-compared against fixture source literals`);
console.log(`evidence rows (3dp / zero / integer-valued literals):`);
for (const x of interesting.slice(0, 6)) console.log(`  row ${x.row}: ${x.got}`);
if (failures > 0) {
  console.error(`FAIL: ${failures} mismatching rows`);
  process.exit(1);
}
console.log("PASS: every literal in the CSV byte-matches the fixture JSON source");
