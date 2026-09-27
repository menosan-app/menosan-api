-- V4__quantity_units.sql — taxonomy version 2 (2026-09-27, ALGORITHM_VERSION 2).
-- Food subcategories are logged in grams; everything else stays in pieces. The unit is fixed per subcategory
-- and matches "unit" in taxonomy.json (see DatabaseIntegrationTest).

ALTER TABLE waste_subcategories
  ADD COLUMN unit text NOT NULL DEFAULT 'PIECES' CHECK (unit IN ('PIECES','GRAMS'));

UPDATE waste_subcategories SET unit = 'GRAMS'
  WHERE code IN ('BIO_FOOD_LEFTOVERS', 'BIO_SPOILED_FOOD', 'BIO_PEELS_SCRAPS');

-- Pieces stay 1–999 (checked by the API per unit); grams go up to 10,000 (10 kg in one entry).
ALTER TABLE waste_entries DROP CONSTRAINT waste_entries_quantity_check;
ALTER TABLE waste_entries ADD CONSTRAINT waste_entries_quantity_check CHECK (quantity BETWEEN 1 AND 10000);

-- One-time reset approved by the team on 2026-09-27: only staging (Neon dev) had data, all of it test data,
-- and existing food entries were counted in pieces. Reports hold v1-shaped stats, so they go too
-- (hotspots, recommendations, adoptions, and impacts cascade). Accounts are kept. Prod didn't exist yet,
-- so on prod this is a no-op on empty tables.
DELETE FROM weekly_reports;
DELETE FROM waste_entries;
