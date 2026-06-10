-- ============================================================================
-- V2: Referential integrity, value constraints, and performance indexes.
--
-- Safe to run against the existing Hibernate-managed schema:
--   * Foreign keys are added with ON DELETE CASCADE / SET NULL and NOT VALID,
--     so existing rows are never re-validated and the migration cannot fail on
--     pre-existing orphans. New writes are fully enforced.
--   * CHECK constraints are NOT VALID for the same reason.
--   * Indexes use IF NOT EXISTS.
--
-- Pre-existing (Hibernate-generated) foreign keys are dropped first so the new
-- cascade behaviour actually takes effect.
-- ============================================================================

-- ---- charger_slots.station_id -> stations(id) ON DELETE CASCADE ----
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel  ON rel.oid  = con.conrelid
        JOIN pg_class fref ON fref.oid = con.confrelid
        WHERE rel.relname = 'charger_slots' AND fref.relname = 'stations' AND con.contype = 'f'
    LOOP
        EXECUTE 'ALTER TABLE charger_slots DROP CONSTRAINT ' || quote_ident(r.conname);
    END LOOP;
    ALTER TABLE charger_slots
        ADD CONSTRAINT fk_charger_slots_station
        FOREIGN KEY (station_id) REFERENCES stations(id) ON DELETE CASCADE NOT VALID;
END $$;

-- ---- reviews.station_id -> stations(id) ON DELETE CASCADE ----
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel  ON rel.oid  = con.conrelid
        JOIN pg_class fref ON fref.oid = con.confrelid
        WHERE rel.relname = 'reviews' AND fref.relname = 'stations' AND con.contype = 'f'
    LOOP
        EXECUTE 'ALTER TABLE reviews DROP CONSTRAINT ' || quote_ident(r.conname);
    END LOOP;
    ALTER TABLE reviews
        ADD CONSTRAINT fk_reviews_station
        FOREIGN KEY (station_id) REFERENCES stations(id) ON DELETE CASCADE NOT VALID;
END $$;

-- ---- reviews.user_id -> app_users(id) ON DELETE SET NULL ----
-- (matches AppUserService.deleteUser which nullifies the reviewer on account deletion)
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel  ON rel.oid  = con.conrelid
        JOIN pg_class fref ON fref.oid = con.confrelid
        WHERE rel.relname = 'reviews' AND fref.relname = 'app_users' AND con.contype = 'f'
    LOOP
        EXECUTE 'ALTER TABLE reviews DROP CONSTRAINT ' || quote_ident(r.conname);
    END LOOP;
    ALTER TABLE reviews
        ADD CONSTRAINT fk_reviews_user
        FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE SET NULL NOT VALID;
END $$;

-- ---- favorites.user_id -> app_users(id) ON DELETE CASCADE ----
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel  ON rel.oid  = con.conrelid
        JOIN pg_class fref ON fref.oid = con.confrelid
        WHERE rel.relname = 'favorites' AND fref.relname = 'app_users' AND con.contype = 'f'
    LOOP
        EXECUTE 'ALTER TABLE favorites DROP CONSTRAINT ' || quote_ident(r.conname);
    END LOOP;
    ALTER TABLE favorites
        ADD CONSTRAINT fk_favorites_user
        FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE NOT VALID;
END $$;

-- ---- favorites.station_id -> stations(id) ON DELETE CASCADE ----
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel  ON rel.oid  = con.conrelid
        JOIN pg_class fref ON fref.oid = con.confrelid
        WHERE rel.relname = 'favorites' AND fref.relname = 'stations' AND con.contype = 'f'
    LOOP
        EXECUTE 'ALTER TABLE favorites DROP CONSTRAINT ' || quote_ident(r.conname);
    END LOOP;
    ALTER TABLE favorites
        ADD CONSTRAINT fk_favorites_station
        FOREIGN KEY (station_id) REFERENCES stations(id) ON DELETE CASCADE NOT VALID;
END $$;

-- ---- user_vehicles.user_id -> app_users(id) ON DELETE CASCADE ----
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel  ON rel.oid  = con.conrelid
        JOIN pg_class fref ON fref.oid = con.confrelid
        WHERE rel.relname = 'user_vehicles' AND fref.relname = 'app_users' AND con.contype = 'f'
    LOOP
        EXECUTE 'ALTER TABLE user_vehicles DROP CONSTRAINT ' || quote_ident(r.conname);
    END LOOP;
    ALTER TABLE user_vehicles
        ADD CONSTRAINT fk_user_vehicles_user
        FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE NOT VALID;
END $$;

-- ---- Value constraints (NOT VALID: enforced on new/updated rows only) ----
ALTER TABLE reviews
    ADD CONSTRAINT chk_reviews_rating CHECK (rating >= 1 AND rating <= 5) NOT VALID;
ALTER TABLE reviews
    ADD CONSTRAINT chk_reviews_comment_len CHECK (comment IS NULL OR char_length(comment) <= 2000) NOT VALID;

-- Restrict connector_type to the known set the app maps to (NOT VALID: new rows only).
ALTER TABLE charger_slots
    ADD CONSTRAINT chk_charger_slots_connector
    CHECK (connector_type IN ('CCS2', 'Type 2', 'CHAdeMO', 'Type 1')) NOT VALID;

-- ---- Performance indexes ----
CREATE INDEX IF NOT EXISTS idx_stations_lat_lng        ON stations (latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_charger_slots_station   ON charger_slots (station_id);
CREATE INDEX IF NOT EXISTS idx_charger_slots_connector ON charger_slots (connector_type);
CREATE INDEX IF NOT EXISTS idx_reviews_station         ON reviews (station_id);
CREATE INDEX IF NOT EXISTS idx_reviews_user            ON reviews (user_id);
CREATE INDEX IF NOT EXISTS idx_favorites_user          ON favorites (user_id);
CREATE INDEX IF NOT EXISTS idx_user_vehicles_user      ON user_vehicles (user_id);
