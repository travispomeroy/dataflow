-- M2.7 (issue #26): delivered files gain their domain shape — [{name, records}, …],
-- sorted by name, captured from the count task when a Run reaches SUCCEEDED. The
-- column was born text with no content to query (V3 anticipated this promotion);
-- every existing value is a valid JSON document ('[]'), so the USING cast carries
-- all rows over.
ALTER TABLE run
    ALTER COLUMN delivered_files TYPE jsonb USING delivered_files::jsonb;
