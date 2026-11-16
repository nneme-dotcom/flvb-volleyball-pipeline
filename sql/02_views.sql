-- =====================================================================
-- 02_views.sql  -  analytical layer
--
-- Every dashboard (Shiny and Power BI) reads from these views, so the
-- business rules live in exactly one place.
--
-- League points (FLVB / FIVB system):
--     3-0 or 3-1 win -> 3 pts     3-2 win -> 2 pts
--     2-3 loss       -> 1 pt      1-3 or 0-3 loss -> 0 pts
-- =====================================================================

-- One row per match with readable labels.
CREATE VIEW v_match AS
SELECT m.match_id,
       m.match_uuid,
       s.season_label                    AS season,
       s.start_year                      AS season_start_year,
       l.league_code,
       l.league_name,
       l.tier                            AS league_tier,
       m.match_no,
       m.match_date,
       m.match_time,
       m.status,
       ht.team_id                        AS home_team_id,
       ht.team_name                      AS home_team,
       at.team_id                        AS away_team_id,
       at.team_name                      AS away_team,
       m.home_sets,
       m.away_sets,
       CASE WHEN m.status = 'played' THEN m.home_sets || '-' || m.away_sets END AS score,
       CASE WHEN m.home_sets = 3 THEN ht.team_name
            WHEN m.away_sets = 3 THEN at.team_name END                          AS winner,
       CASE WHEN m.status = 'played' THEN m.home_sets > m.away_sets END         AS home_win,
       (SELECT SUM(ss.home_points) FROM set_score ss WHERE ss.match_id = m.match_id) AS home_points,
       (SELECT SUM(ss.away_points) FROM set_score ss WHERE ss.match_id = m.match_id) AS away_points,
       m.duration_min,
       m.is_forfeit,
       v.venue_name                      AS venue
FROM match m
JOIN season s  ON s.season_id  = m.season_id
JOIN league l  ON l.league_id  = m.league_id
JOIN team   ht ON ht.team_id   = m.home_team_id
JOIN team   at ON at.team_id   = m.away_team_id
LEFT JOIN venue v ON v.venue_id = m.venue_id;

-- One row per set actually played (forfeits excluded: 25-0 sets are
-- administrative results and would distort every score-based metric).
CREATE VIEW v_set AS
SELECT m.match_id,
       m.season,
       m.league_code,
       m.league_name,
       m.match_date,
       m.home_team,
       m.away_team,
       ss.set_no,
       ss.home_points,
       ss.away_points,
       ss.home_points + ss.away_points          AS total_points,
       ABS(ss.home_points - ss.away_points)     AS margin,
       ss.home_points > ss.away_points          AS home_won_set,
       -- "extra points": the set went past 25 (15 in the tie-break)
       MAX(ss.home_points, ss.away_points) > CASE WHEN ss.set_no = 5 THEN 15 ELSE 25 END AS went_to_extra_points,
       ss.set_no = m.home_sets + m.away_sets    AS is_deciding_set
FROM set_score ss
JOIN v_match m ON m.match_id = ss.match_id
WHERE m.is_forfeit = 0;

-- Unpivot: two rows per played match, one from each team's point of view.
-- This is the main fact table for team-level analysis.
CREATE VIEW v_team_match AS
SELECT m.match_id, m.season, m.season_start_year, m.league_code, m.league_name,
       m.match_date, m.match_no,
       m.home_team_id AS team_id,  m.home_team AS team,
       m.away_team_id AS opponent_id, m.away_team AS opponent,
       'Home'         AS venue_type,
       m.home_sets    AS sets_for,   m.away_sets   AS sets_against,
       m.home_points  AS points_for, m.away_points AS points_against,
       m.home_sets > m.away_sets AS won,
       m.home_sets || '-' || m.away_sets AS score,
       CASE WHEN m.home_sets = 3 AND m.away_sets <= 1 THEN 3
            WHEN m.home_sets = 3                      THEN 2
            WHEN m.home_sets = 2                      THEN 1
            ELSE 0 END AS league_points,
       m.duration_min, m.is_forfeit
FROM v_match m
WHERE m.status = 'played'
UNION ALL
SELECT m.match_id, m.season, m.season_start_year, m.league_code, m.league_name,
       m.match_date, m.match_no,
       m.away_team_id, m.away_team,
       m.home_team_id, m.home_team,
       'Away',
       m.away_sets,   m.home_sets,
       m.away_points, m.home_points,
       m.away_sets > m.home_sets,
       m.away_sets || '-' || m.home_sets,
       CASE WHEN m.away_sets = 3 AND m.home_sets <= 1 THEN 3
            WHEN m.away_sets = 3                      THEN 2
            WHEN m.away_sets = 2                      THEN 1
            ELSE 0 END,
       m.duration_min, m.is_forfeit
FROM v_match m
WHERE m.status = 'played';

-- League table per season and division, ranked with a window function.
-- Tie-breakers: points, wins, set ratio, ball (rally point) ratio.
CREATE VIEW v_standings AS
WITH agg AS (
    SELECT season, season_start_year, league_code, league_name, team_id, team,
           COUNT(*)                          AS played,
           SUM(won)                          AS won,
           SUM(1 - won)                      AS lost,
           SUM(score = '3-0')                AS w_3_0,
           SUM(score = '3-1')                AS w_3_1,
           SUM(score = '3-2')                AS w_3_2,
           SUM(score = '2-3')                AS l_2_3,
           SUM(score = '1-3')                AS l_1_3,
           SUM(score = '0-3')                AS l_0_3,
           SUM(league_points)                AS points,
           SUM(sets_for)                     AS sets_won,
           SUM(sets_against)                 AS sets_lost,
           SUM(points_for)                   AS balls_won,
           SUM(points_against)               AS balls_lost
    FROM v_team_match
    GROUP BY season, season_start_year, league_code, league_name, team_id, team
)
SELECT RANK() OVER (
           PARTITION BY season, league_code
           ORDER BY points DESC,
                    won DESC,
                    CASE WHEN sets_lost  = 0 THEN 1e9 ELSE 1.0 * sets_won  / sets_lost  END DESC,
                    CASE WHEN balls_lost = 0 THEN 1e9 ELSE 1.0 * balls_won / balls_lost END DESC
       )                                               AS rank,
       agg.*,
       ROUND(100.0 * won / played, 1)                  AS win_pct,
       ROUND(1.0 * sets_won  / NULLIF(sets_lost, 0), 3)  AS set_ratio,
       ROUND(1.0 * balls_won / NULLIF(balls_lost, 0), 3) AS ball_ratio,
       ROUND(1.0 * points / played, 2)                 AS points_per_match
FROM agg;

-- Home vs away split per team and season.
CREATE VIEW v_home_away AS
SELECT season, season_start_year, league_code, league_name, team_id, team, venue_type,
       COUNT(*)                                   AS played,
       SUM(won)                                   AS won,
       ROUND(100.0 * SUM(won) / COUNT(*), 1)      AS win_pct,
       SUM(sets_for)                              AS sets_won,
       SUM(sets_against)                          AS sets_lost,
       ROUND(1.0 * SUM(league_points) / COUNT(*), 2) AS points_per_match
FROM v_team_match
WHERE is_forfeit = 0
GROUP BY season, season_start_year, league_code, league_name, team_id, team, venue_type;

-- Is there a home-court advantage? One row per season and division.
CREATE VIEW v_league_home_advantage AS
SELECT season, season_start_year, league_code, league_name,
       COUNT(*)                                        AS matches,
       SUM(home_win)                                   AS home_wins,
       ROUND(100.0 * SUM(home_win) / COUNT(*), 1)      AS home_win_pct,
       ROUND(AVG(home_sets - away_sets), 2)            AS avg_set_diff_home,
       ROUND(AVG(home_points - away_points), 1)        AS avg_point_diff_home
FROM v_match
WHERE status = 'played' AND is_forfeit = 0
GROUP BY season, season_start_year, league_code, league_name;

-- How sets evolve within a match: tightness and length by set number.
CREATE VIEW v_set_trends AS
SELECT season, league_code, league_name, set_no,
       COUNT(*)                                          AS sets_played,
       ROUND(AVG(total_points), 1)                       AS avg_total_points,
       ROUND(AVG(margin), 2)                             AS avg_margin,
       ROUND(100.0 * SUM(margin = 2) / COUNT(*), 1)      AS close_set_pct,      -- decided by the minimum 2 points
       ROUND(100.0 * SUM(went_to_extra_points) / COUNT(*), 1) AS extra_points_pct,
       ROUND(100.0 * SUM(home_won_set) / COUNT(*), 1)    AS home_set_win_pct
FROM v_set
GROUP BY season, league_code, league_name, set_no;

-- Set-by-set profile of each team (strong starters vs. strong finishers).
CREATE VIEW v_team_set_profile AS
SELECT season, league_code, team, set_no,
       COUNT(*)                                  AS sets_played,
       SUM(won_set)                              AS sets_won,
       ROUND(100.0 * SUM(won_set) / COUNT(*), 1) AS set_win_pct,
       ROUND(AVG(pts_for - pts_against), 2)      AS avg_point_diff
FROM (
    SELECT season, league_code, home_team AS team, set_no,
           home_won_set AS won_set, home_points AS pts_for, away_points AS pts_against
    FROM v_set
    UNION ALL
    SELECT season, league_code, away_team, set_no,
           1 - home_won_set, away_points, home_points
    FROM v_set
)
GROUP BY season, league_code, team, set_no;

-- Running form: cumulative league points and rolling last-5 win rate.
CREATE VIEW v_team_form AS
SELECT season, league_code, team_id, team, match_id, match_date, opponent, venue_type, score, won,
       ROW_NUMBER() OVER w                                      AS match_seq,
       SUM(league_points) OVER w                                AS cumulative_points,
       ROUND(100.0 * AVG(won) OVER (w ROWS BETWEEN 4 PRECEDING AND CURRENT ROW), 1) AS last5_win_pct
FROM v_team_match
WINDOW w AS (PARTITION BY season, league_code, team_id ORDER BY match_date, match_id);

-- All-time head-to-head record between two teams (ordered pair).
CREATE VIEW v_head_to_head AS
SELECT team, opponent,
       COUNT(*)          AS played,
       SUM(won)          AS won,
       SUM(1 - won)      AS lost,
       SUM(sets_for)     AS sets_won,
       SUM(sets_against) AS sets_lost,
       MAX(match_date)   AS last_meeting
FROM v_team_match
WHERE is_forfeit = 0
GROUP BY team, opponent;

-- Data-quality control: our computed standings vs. the published table.
-- Any row with all_match = 0 means the pipeline and the source disagree.
CREATE VIEW v_standings_reconciliation AS
SELECT s.season_label AS season, l.league_code, t.team_name AS team,
       o.rank   AS official_rank,   c.rank                AS computed_rank,
       o.played AS official_played, COALESCE(c.played, 0) AS computed_played,
       o.won    AS official_won,    COALESCE(c.won, 0)    AS computed_won,
       o.points AS official_points, COALESCE(c.points, 0) AS computed_points,
       o.sets_won  AS official_sets_won,  COALESCE(c.sets_won, 0)  AS computed_sets_won,
       o.balls_won AS official_balls_won, COALESCE(c.balls_won, 0) AS computed_balls_won,
       -- a team listed with zero matches has no computed row, hence the COALESCEs
       (    o.played    = COALESCE(c.played, 0)    AND o.won = COALESCE(c.won, 0)
        AND o.sets_won  = COALESCE(c.sets_won, 0)  AND o.sets_lost  = COALESCE(c.sets_lost, 0)
        AND o.balls_won = COALESCE(c.balls_won, 0) AND o.balls_lost = COALESCE(c.balls_lost, 0)) AS results_match,
       o.points = COALESCE(c.points, 0)     AS points_match,
       COALESCE(o.rank = c.rank, o.played = 0) AS rank_match
FROM official_standing o
JOIN season s ON s.season_id = o.season_id
JOIN league l ON l.league_id = o.league_id
JOIN team   t ON t.team_id   = o.team_id
LEFT JOIN v_standings c
       ON c.season = s.season_label AND c.league_code = l.league_code AND c.team_id = o.team_id;

-- One-row health check shown at the end of every pipeline run.
CREATE VIEW v_dq_summary AS
SELECT (SELECT COUNT(*) FROM stg_match_raw)                                AS raw_rows,
       (SELECT COUNT(*) FROM match)                                        AS matches,
       (SELECT COUNT(*) FROM match WHERE status = 'played')                AS played_matches,
       (SELECT COUNT(*) FROM match WHERE status = 'scheduled')             AS scheduled_matches,
       (SELECT COUNT(*) FROM match WHERE is_forfeit = 1)                   AS forfeits,
       (SELECT COUNT(*) FROM set_score)                                    AS sets,
       (SELECT COUNT(*) FROM team)                                         AS teams,
       (SELECT COUNT(*) FROM etl_reject WHERE severity = 'REJECT')         AS rejected_rows,
       (SELECT COUNT(*) FROM etl_reject WHERE severity = 'WARNING')        AS warnings,
       -- sets stored must equal sets implied by the match score
       (SELECT COUNT(*) FROM match m
         WHERE m.status = 'played'
           AND m.home_sets + m.away_sets <> (SELECT COUNT(*) FROM set_score ss WHERE ss.match_id = m.match_id)) AS matches_with_set_count_mismatch,
       (SELECT COUNT(*) FROM v_standings_reconciliation WHERE results_match = 1) AS standings_rows_reconciled,
       (SELECT COUNT(*) FROM v_standings_reconciliation)                         AS standings_rows_checked;
