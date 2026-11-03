-- GB-wide NaPTAN stops (bus + rail). Refreshed by NaptanLoader via TRUNCATE + COPY.
CREATE TABLE stops (
    atco_code            TEXT NOT NULL PRIMARY KEY,
    common_name          TEXT NOT NULL,
    indicator            TEXT NOT NULL DEFAULT '',
    locality_name        TEXT NOT NULL DEFAULT '',
    parent_locality_name TEXT NOT NULL DEFAULT '',
    location             GEOGRAPHY(POINT, 4326) NOT NULL,
    stop_type            TEXT NOT NULL,           -- BCT/BCS/BCQ = bus, RSE/RLY/MET = rail/metro
    status               TEXT NOT NULL DEFAULT 'active',
    stop_rank            SMALLINT NOT NULL DEFAULT 5,  -- rail 10 > interchange > stop; viewport priority
    -- Denormalised lower-cased search target; the trigram index lives on this.
    search_text          TEXT NOT NULL GENERATED ALWAYS AS (
                             lower(common_name || ' ' || locality_name || ' ' || parent_locality_name)
                         ) STORED
);

-- Partial GiST indexes for the viewport query (only the active stops we actually draw).
CREATE INDEX stops_loc_bus_gix  ON stops USING GIST (location)
    WHERE status = 'active' AND stop_type IN ('BCT', 'BCS', 'BCQ');
CREATE INDEX stops_loc_rail_gix ON stops USING GIST (location)
    WHERE status = 'active' AND stop_type IN ('RSE', 'RLY', 'MET');

-- Trigram autocomplete on name + locality.
CREATE INDEX stops_search_trgm  ON stops USING GIN (search_text gin_trgm_ops);
