import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The M3 exit gate's scenario (spec #28): the whole Positions Feed lifecycle —
 * create, compose on the canvas, deploy, run, watch the delivery land,
 * undeploy, delete — entirely through the browser. The only non-UI inputs are
 * the committed goldens the run record is pinned against; the delivered
 * *bytes* are re-verified by the gate script afterwards, off the SFTP server.
 *
 * Deliberate non-assertion: the JSON preview is never byte-compared to
 * e2e/canonical/positions-feed.config.json — that would handcuff id minting,
 * and byte-level proof lives at the delivered-file layer.
 */

// Pinned by spec #19: inside the fixtures' trade-date window, so the delivered
// names — and the run record derived from them — are fully golden.
const BUSINESS_DATE = '2026-07-17';

// The three canonical clients (e2e/canonical/positions-feed.config.json picks
// INV-001..003), chosen in the UI by their reference-data names.
const CLIENTS = ['Alice Thornton', 'Benjamin Okafor', 'Carmen Delgado'];

// What run history must show was shipped: name + data-row count per committed
// golden CSV (lines minus header minus the contractual trailing newline) —
// the m2-walkthrough's assertion, read through the browser.
const GOLDEN_DIR = join(import.meta.dirname, '../../../../e2e/golden/delivered');
const GOLDEN_DELIVERED = readdirSync(GOLDEN_DIR)
  .sort()
  .map((name) => ({
    name,
    records: readFileSync(join(GOLDEN_DIR, name), 'utf8').split('\n').length - 2,
  }));

const canvasNode = (page: Page, nodeId: string): Locator =>
  page.locator(`.react-flow__node[data-id="${nodeId}"]`);

/** Draws an edge the way a user does: press on a handle, drag, release. */
async function connect(page: Page, fromId: string, toId: string): Promise<void> {
  await canvasNode(page, fromId).locator('.react-flow__handle-right').hover();
  await page.mouse.down();
  const target = canvasNode(page, toId).locator('.react-flow__handle-left');
  const box = await target.boundingBox();
  if (box === null) {
    throw new Error(`no bounding box for the ${toId} target handle`);
  }
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 10 });
  await page.mouse.up();
}

test('the Positions Feed lifecycle, entirely in the browser', async ({ page }) => {
  // -- Create: name first, land on the blank canvas ---------------------------
  await page.goto('/');
  await page.getByRole('button', { name: 'New Dataflow' }).click();
  await page.getByLabel('Name', { exact: true }).fill('Positions Feed');
  await page.getByRole('button', { name: 'Create' }).click();
  await expect(page).toHaveURL(/\/dataflows\/[0-9a-f-]{36}$/);

  // Deploy is gated from the first moment: the empty Draft already fails the
  // courtesy validation, and the property panel says why in glossary language.
  const deploy = page.getByRole('button', { name: 'Deploy' });
  await expect(deploy).toBeDisabled();
  await expect(page.getByText('Before this can deploy')).toBeVisible();
  await expect(
    page.getByText('a Dataflow delivers to exactly one Destination'),
  ).toBeVisible();

  // -- Compose: drag the palette onto the canvas, connect the path ------------
  // Drop positions stay well inside the canvas (~620px wide at the default
  // 1280px viewport) — a point past its edge lands on the property panel.
  const canvas = page.locator('.react-flow');
  await page
    .getByLabel('Positions', { exact: true })
    .dragTo(canvas, { targetPosition: { x: 60, y: 60 } });
  await page
    .getByLabel('Filter by Clients', { exact: true })
    .dragTo(canvas, { targetPosition: { x: 260, y: 180 } });
  await page
    .getByLabel('Pomeroy Provider', { exact: true })
    .dragTo(canvas, { targetPosition: { x: 440, y: 300 } });

  // The ids are minted from what each node is — the readable, diffable kind.
  await expect(canvasNode(page, 'positions-source')).toBeVisible();
  await expect(canvasNode(page, 'client-filter')).toBeVisible();
  await expect(canvasNode(page, 'pomeroy-provider-delivery')).toBeVisible();

  await connect(page, 'positions-source', 'client-filter');
  await connect(page, 'client-filter', 'pomeroy-provider-delivery');
  await expect(page.locator('.react-flow__edge')).toHaveCount(2);

  // -- The filter's Clients, from reference data ------------------------------
  await canvasNode(page, 'client-filter').click();
  for (const name of CLIENTS) {
    // Role-specific: with the popup open, its listbox carries the same label.
    const input = page.getByRole('combobox', { name: 'Clients' });
    await input.click();
    await input.fill(name);
    await page.getByRole('option', { name }).click();
  }
  await expect(canvasNode(page, 'client-filter')).toContainText('Filter by Clients (3)');

  // -- Dataflow settings: Schedule, then the Operator half --------------------
  await page.locator('.react-flow__pane').click({ position: { x: 20, y: 20 } });
  await page.getByLabel('Runs', { exact: true }).click();
  await page.getByRole('option', { name: 'Daily' }).click();
  await page.getByLabel('Time', { exact: true }).fill('06:30');
  const timezone = page.getByRole('combobox', { name: 'Timezone' });
  await timezone.click();
  await timezone.fill('America/New_York');
  await page.getByRole('option', { name: 'America/New_York' }).click();

  await page.getByLabel('Engine', { exact: true }).click();
  await page.getByRole('option', { name: 'Hop' }).click();
  await page.getByLabel('Execution Model', { exact: true }).click();
  await page.getByRole('option', { name: 'Batch' }).click();

  // The courtesy validation now passes — what still gates Deploy is the
  // explicit Save: what you deploy is what you saw persisted.
  await expect(page.getByText('Before this can deploy')).toHaveCount(0);
  await expect(page.getByText('Save your changes before deploying.')).toBeVisible();
  await expect(deploy).toBeDisabled();

  // -- Save, then Deploy ------------------------------------------------------
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Ready to deploy.')).toBeVisible();
  await expect(deploy).toBeEnabled();
  await deploy.click();
  await expect(page.getByText('Deployed v1')).toBeVisible({ timeout: 30_000 });

  // -- Run Now, with the golden Business Date ---------------------------------
  await page.getByRole('button', { name: 'Run Now' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Business Date', { exact: true }).fill(BUSINESS_DATE);
  await dialog.getByRole('button', { name: 'Run Now' }).click();

  // Confirming lands on run history, which polls the Run live to terminal.
  // Assertions pin the NEWEST row only: Kestra keys executions by flow id
  // (= the slug), so earlier same-slug incarnations' runs — the m1/m2
  // walkthroughs in the gate chain — legitimately share this history.
  await expect(page).toHaveURL(/\/runs$/, { timeout: 30_000 });
  const newestRun = page.getByRole('row').nth(1);
  await expect(newestRun.getByText('SUCCEEDED')).toBeVisible({ timeout: 240_000 });
  await expect(
    newestRun.getByRole('cell', { name: BUSINESS_DATE, exact: true }),
  ).toBeVisible();

  // Exactly the five golden files, with the golden record counts.
  for (const file of GOLDEN_DELIVERED) {
    await expect(newestRun.getByText(`${file.name} — ${file.records} records`)).toBeVisible();
  }
  await expect(newestRun.locator('li')).toHaveCount(GOLDEN_DELIVERED.length);

  // -- Undeploy, then delete, from the card (the M2 lesson: leave no Schedule
  // firing in the left-running world) -----------------------------------------
  await page.getByRole('link', { name: 'Dataflow', exact: true }).click();
  const card = page.getByTestId('dataflow-card').filter({ hasText: 'Positions Feed' });
  await expect(card.getByText('Deployed v1')).toBeVisible();

  await card.getByRole('button', { name: 'Undeploy' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Undeploy' }).click();
  await expect(card.getByText('Draft — not deployed')).toBeVisible();

  await card.getByRole('button', { name: 'Delete' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click();
  // The card is gone — asserted on the card itself, not an empty list, so the
  // scenario stays honest even in a world holding unrelated Dataflows.
  await expect(card).toHaveCount(0);
});
