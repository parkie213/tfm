-- NPTG localities (GB gazetteer of towns/villages/places). Refreshed by NptgLocalityLoader via
-- TRUNCATE + COPY, mirroring the stops table. Used as "place" results in stop search, ranked above
-- stops. Source coordinates are British National Grid (EPSG:27700) and converted to WGS84 on insert.
CREATE TABLE nptg_localities (
    code         TEXT NOT NULL PRIMARY KEY,   -- NptgLocalityCode
    name         TEXT NOT NULL,               -- LocalityName
    qualifier    TEXT NOT NULL DEFAULT '',    -- QualifierName (disambiguating district/area)
    parent_name  TEXT NOT NULL DEFAULT '',    -- ParentLocalityName
    location     GEOGRAPHY(POINT, 4326) NOT NULL
);

-- Trigram autocomplete on the locality NAME only. Place search matches on the name the user typed —
-- NOT the qualifier/parent — so "loughborough" returns Loughborough (the town), not "North End" whose
-- qualifier merely happens to be Loughborough.
CREATE INDEX nptg_localities_name_trgm ON nptg_localities USING GIN (lower(name) gin_trgm_ops);
-- GiST on the centroid (cheap; supports the proximity-bias distance and any future viewport use).
CREATE INDEX nptg_localities_loc_gix ON nptg_localities USING GIST (location);
