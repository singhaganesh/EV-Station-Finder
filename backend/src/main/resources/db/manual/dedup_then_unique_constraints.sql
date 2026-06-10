-- ============================================================================
-- MANUAL, DESTRUCTIVE migration. NOT run by Flyway automatically.
--
-- Adds the UNIQUE constraints the application logic depends on
-- (one review per user per station; station deduplication). These cannot be
-- added automatically because creating a UNIQUE index FAILS if duplicate rows
-- already exist -- so this script first DELETES duplicates.
--
-- >>> TAKE A DATABASE BACKUP BEFORE RUNNING THIS. <<<
--
-- Run it manually (psql / Supabase SQL editor) once you have verified the
-- duplicate counts below are what you expect.
-- ============================================================================

BEGIN;

-- 1. Inspect duplicates first (review the output before deleting):
--    SELECT user_id, station_id, count(*) FROM reviews GROUP BY 1,2 HAVING count(*) > 1;
--    SELECT name, latitude, longitude, count(*) FROM stations GROUP BY 1,2,3 HAVING count(*) > 1;

-- 2. De-duplicate reviews: keep the lowest id per (user_id, station_id).
DELETE FROM reviews r
USING reviews dup
WHERE r.user_id = dup.user_id
  AND r.station_id = dup.station_id
  AND r.user_id IS NOT NULL
  AND r.id > dup.id;

ALTER TABLE reviews
    ADD CONSTRAINT uq_reviews_user_station UNIQUE (user_id, station_id);

-- 3. De-duplicate stations by (name, latitude, longitude): keep the lowest id.
--    Children cascade-delete via the FKs added in V2.
DELETE FROM stations s
USING stations dup
WHERE s.name = dup.name
  AND s.latitude = dup.latitude
  AND s.longitude = dup.longitude
  AND s.id > dup.id;

CREATE UNIQUE INDEX IF NOT EXISTS idx_stations_dedup
    ON stations (name, latitude, longitude);

-- 4. Enforce unique ocm_id (ignored NULLs are allowed by Postgres unique indexes).
CREATE UNIQUE INDEX IF NOT EXISTS uq_stations_ocm_id
    ON stations (ocm_id) WHERE ocm_id IS NOT NULL;

-- 5. Now that data is clean, validate the V2 constraints (optional but recommended):
--    ALTER TABLE reviews VALIDATE CONSTRAINT chk_reviews_rating;
--    ALTER TABLE charger_slots VALIDATE CONSTRAINT fk_charger_slots_station;
--    ... (repeat per constraint) ...

COMMIT;
