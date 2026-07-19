// M4.2 spike — explicit all-string Avro schemas (the M2 verbatim-numerics lesson:
// no schema inference anywhere; every reader/writer carries these).
const str = (name) => ({ name, type: 'string' });
const nstr = (name) => ({ name, type: ['null', 'string'], default: null });
const record = (name, fields) => JSON.stringify({ type: 'record', name, fields });

// Page-element schemas: the fields the plan extracts, source-API names.
// Unlisted envelope fields (positionId, …) are dropped by the reader.
export const POSITIONS_ROW = record('positions_row',
  ['investorId', 'symbol', 'assetClass', 'quantity', 'marketValue', 'currency'].map(str));
export const INVESTORS_ROW = record('investors_row',
  ['investorId', 'name', 'advisorGroup'].map(str));
export const ORDERS_ROW = record('orders_row',
  ['investorId', 'symbol', 'orderId', 'side', 'quantity', 'status', 'tradeDate'].map(str));

// Join stage 1 output: positions ⋈ investors (left), renamed to feed columns.
export const JOIN1_ROW = record('join1_row', [
  str('clientId'), nstr('clientName'), nstr('advisorGroup'),
  str('symbol'), str('assetClass'), str('quantity'), str('marketValue'), str('currency'),
]);

// Join stage 2 output: the 13 delivered columns; order columns null when no order.
export const FEED_ROW = record('feed_row', [
  str('clientId'), nstr('clientName'), nstr('advisorGroup'),
  str('symbol'), str('assetClass'), str('quantity'), str('marketValue'), str('currency'),
  nstr('orderId'), nstr('orderSide'), nstr('orderQuantity'), nstr('orderStatus'), nstr('tradeDate'),
]);
