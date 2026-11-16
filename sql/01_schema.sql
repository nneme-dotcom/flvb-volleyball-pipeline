-- =====================================================================
-- 01_schema.sql  -  relational model for FLVB match results (SQLite)
--
-- Layers
--   stg_*      raw text exactly as ingested (audit / replay)
--   etl_*      pipeline metadata: runs and rejected records
--   core       season, league, club, team, venue, match, set_score
--   reference  official_standing (published table, used for reconciliation)
--
-- The script is idempotent: the Java loader drops and rebuilds everything
-- on each run (full refresh), which keeps the pipeline reproducible.
-- =====================================================================

DROP VIEW  IF EXISTS v_dq_summary;
DROP VIEW  IF EXISTS v_standings_reconciliation;
DROP VIEW  IF EXISTS v_head_to_head;
DROP VIEW  IF EXISTS v_team_form;
DROP VIEW  IF EXISTS v_team_set_profile;
DROP VIEW  IF EXISTS v_set_trends;
DROP VIEW  IF EXISTS v_league_home_advantage;
DROP VIEW  IF EXISTS v_home_away;
DROP VIEW  IF EXISTS v_standings;
DROP VIEW  IF EXISTS v_team_match;
DROP VIEW  IF EXISTS v_set;
DROP VIEW  IF EXISTS v_match;
DROP TABLE IF EXISTS official_standing;
DROP TABLE IF EXISTS set_score;
DROP TABLE IF EXISTS match;
DROP TABLE IF EXISTS venue;
DROP TABLE IF EXISTS team;
DROP TABLE IF EXISTS club;
DROP TABLE IF EXISTS league;
DROP TABLE IF EXISTS season;
DROP TABLE IF EXISTS etl_reject;
DROP TABLE IF EXISTS etl_run;
DROP TABLE IF EXISTS stg_match_raw;

-- ---------------------------------------------------------------- staging
CREATE TABLE stg_match_raw (
    line_no      INTEGER PRIMARY KEY,   -- line number in the source file
    season       TEXT,
    league       TEXT,
    match_date   TEXT,
    match_time   TEXT,
    match_no     TEXT,
    home_team    TEXT,
    away_team    TEXT,
    result       TEXT,
    set_scores   TEXT,
    duration_min TEXT,
    venue        TEXT,
    match_uuid   TEXT
);

-- --------------------------------------------------------------- metadata
CREATE TABLE etl_run (
    run_id            INTEGER PRIMARY KEY,
    started_at        TEXT    NOT NULL,
    finished_at       TEXT,
    source_file       TEXT    NOT NULL,
    rows_read         INTEGER,
    matches_loaded    INTEGER,
    sets_loaded       INTEGER,
    rows_rejected     INTEGER,
    warnings          INTEGER
);

CREATE TABLE etl_reject (
    reject_id    INTEGER PRIMARY KEY,
    source_file  TEXT    NOT NULL,
    line_no      INTEGER NOT NULL,
    severity     TEXT    NOT NULL CHECK (severity IN ('REJECT', 'WARNING')),
    reason_code  TEXT    NOT NULL,      -- e.g. DUPLICATE_MATCH, UNKNOWN_TEAM
    detail       TEXT,
    raw_record   TEXT
);

-- ------------------------------------------------------------- dimensions
CREATE TABLE season (
    season_id    INTEGER PRIMARY KEY,
    season_label TEXT    NOT NULL UNIQUE,              -- '2026/2027'
    start_year   INTEGER NOT NULL UNIQUE CHECK (start_year BETWEEN 2000 AND 2100)
);

CREATE TABLE league (
    league_id    INTEGER PRIMARY KEY,
    league_code  TEXT    NOT NULL UNIQUE,              -- 'MEN_DN'
    league_name  TEXT    NOT NULL,
    tier         INTEGER NOT NULL CHECK (tier >= 1)    -- 1 = top division
);

CREATE TABLE club (
    club_id      INTEGER PRIMARY KEY,
    club_code    TEXT    NOT NULL UNIQUE,              -- 'VCS'
    club_name    TEXT    NOT NULL UNIQUE               -- 'VC Stroossen'
);

-- A club can enter several squads (VC Stroossen 1, 2, 3 ...).
CREATE TABLE team (
    team_id      INTEGER PRIMARY KEY,
    club_id      INTEGER NOT NULL REFERENCES club (club_id),
    squad_no     INTEGER NOT NULL CHECK (squad_no BETWEEN 1 AND 9),
    team_name    TEXT    NOT NULL UNIQUE,              -- 'VC Stroossen 1'
    UNIQUE (club_id, squad_no)
);

CREATE TABLE venue (
    venue_id     INTEGER PRIMARY KEY,
    venue_name   TEXT    NOT NULL UNIQUE
);

-- ------------------------------------------------------------------ facts
CREATE TABLE match (
    match_id      INTEGER PRIMARY KEY,
    match_uuid    TEXT    NOT NULL UNIQUE,             -- natural key from the federation system
    season_id     INTEGER NOT NULL REFERENCES season (season_id),
    league_id     INTEGER NOT NULL REFERENCES league (league_id),
    match_no      INTEGER NOT NULL,
    match_date    TEXT    NOT NULL CHECK (match_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    match_time    TEXT             CHECK (match_time IS NULL OR match_time GLOB '[0-2][0-9]:[0-5][0-9]'),
    home_team_id  INTEGER NOT NULL REFERENCES team (team_id),
    away_team_id  INTEGER NOT NULL REFERENCES team (team_id),
    venue_id      INTEGER          REFERENCES venue (venue_id),
    status        TEXT    NOT NULL CHECK (status IN ('played', 'scheduled')),
    home_sets     INTEGER          CHECK (home_sets BETWEEN 0 AND 3),
    away_sets     INTEGER          CHECK (away_sets BETWEEN 0 AND 3),
    duration_min  INTEGER          CHECK (duration_min BETWEEN 1 AND 300),
    is_forfeit    INTEGER NOT NULL DEFAULT 0 CHECK (is_forfeit IN (0, 1)),
    UNIQUE (season_id, league_id, match_no),
    CHECK (home_team_id <> away_team_id),
    -- a played match is best-of-five: exactly one side reaches 3 sets
    CHECK (
        (status = 'scheduled' AND home_sets IS NULL AND away_sets IS NULL)
     OR (status = 'played'    AND (home_sets = 3) <> (away_sets = 3))
    )
);

CREATE TABLE set_score (
    match_id     INTEGER NOT NULL REFERENCES match (match_id) ON DELETE CASCADE,
    set_no       INTEGER NOT NULL CHECK (set_no BETWEEN 1 AND 5),
    home_points  INTEGER NOT NULL CHECK (home_points >= 0),
    away_points  INTEGER NOT NULL CHECK (away_points >= 0),
    PRIMARY KEY (match_id, set_no),
    CHECK (home_points <> away_points)                 -- a set always has a winner
) WITHOUT ROWID;

-- Standings table as published by the federation; loaded only to verify
-- that the standings we compute from match data agree with the source.
CREATE TABLE official_standing (
    season_id    INTEGER NOT NULL REFERENCES season (season_id),
    league_id    INTEGER NOT NULL REFERENCES league (league_id),
    team_id      INTEGER NOT NULL REFERENCES team (team_id),
    rank         INTEGER NOT NULL,
    played       INTEGER NOT NULL,
    won          INTEGER NOT NULL,
    lost         INTEGER NOT NULL,
    points       INTEGER NOT NULL,
    sets_won     INTEGER NOT NULL,
    sets_lost    INTEGER NOT NULL,
    balls_won    INTEGER NOT NULL,
    balls_lost   INTEGER NOT NULL,
    PRIMARY KEY (season_id, league_id, team_id)
) WITHOUT ROWID;

-- ---------------------------------------------------------------- indexes
-- Foreign keys and the columns the dashboards filter on.
CREATE INDEX ix_match_season_league ON match (season_id, league_id, match_date);
CREATE INDEX ix_match_home_team     ON match (home_team_id, season_id);
CREATE INDEX ix_match_away_team     ON match (away_team_id, season_id);
CREATE INDEX ix_match_date          ON match (match_date);
-- Partial index: most analytical queries only read played matches.
CREATE INDEX ix_match_played        ON match (season_id, league_id) WHERE status = 'played';
CREATE INDEX ix_team_club           ON team (club_id);
CREATE INDEX ix_reject_reason       ON etl_reject (reason_code);
