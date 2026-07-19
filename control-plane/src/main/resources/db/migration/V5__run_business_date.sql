-- Business Date joins the Run record (issue #29): the as-of date the Run generated,
-- resolved at record time by the same rule the compiled flow uses (#25) — the explicit
-- businessDate input when the trigger carried one, else the run date in the active
-- Deployment's Schedule timezone (UTC when manual-only). Nullable for honesty only:
-- rows recorded before this column existed have no knowable value; every new record
-- resolves one.
ALTER TABLE run ADD COLUMN business_date date;
