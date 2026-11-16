-- =====================================================================
-- 03_analysis_queries.sql  -  example analytical queries
--
-- Run against the database built by the pipeline, e.g.
--     sqlite3 -header -column build/volleyball.db < sql/03_analysis_queries.sql
-- =====================================================================

-- Q1. Current top-division table.
SELECT rank, team, played, won, lost, points, sets_won || ':' || sets_lost AS sets, ball_ratio
FROM v_standings
WHERE league_code = 'MEN_DN'
  AND season = (SELECT MAX(season_label) FROM season)
ORDER BY rank;

-- Q2. Champions (regular-season winners) of the top division, by season.
SELECT season, team AS regular_season_winner, played, won, points
FROM v_standings
WHERE league_code = 'MEN_DN' AND rank = 1
ORDER BY season;

-- Q3. Home-court advantage per division, all seasons pooled.
SELECT league_name,
       SUM(matches)                                    AS matches,
       ROUND(100.0 * SUM(home_wins) / SUM(matches), 1) AS home_win_pct
FROM v_league_home_advantage
GROUP BY league_name
ORDER BY home_win_pct DESC;

-- Q4. Teams whose results depend most on playing at home
--     (difference between home and away win rate, min. 5 matches each).
SELECT h.season, h.team,
       h.win_pct AS home_win_pct, a.win_pct AS away_win_pct,
       ROUND(h.win_pct - a.win_pct, 1) AS home_boost
FROM v_home_away h
JOIN v_home_away a
  ON a.season = h.season AND a.league_code = h.league_code AND a.team_id = h.team_id
WHERE h.venue_type = 'Home' AND a.venue_type = 'Away'
  AND h.played >= 5 AND a.played >= 5
ORDER BY home_boost DESC
LIMIT 10;

-- Q5. How often does the winner of set 1 win the match?
SELECT COUNT(*)                                                  AS matches,
       ROUND(100.0 * SUM(s.home_won_set = m.home_win) / COUNT(*), 1) AS set1_winner_wins_match_pct
FROM v_set s
JOIN v_match m ON m.match_id = s.match_id
WHERE s.set_no = 1;

-- Q6. Tie-break (five-set) specialists: record in 3-2 / 2-3 matches.
SELECT team,
       COUNT(*)                              AS five_setters,
       SUM(won)                              AS won,
       ROUND(100.0 * SUM(won) / COUNT(*), 1) AS win_pct
FROM v_team_match
WHERE sets_for + sets_against = 5
GROUP BY team
HAVING COUNT(*) >= 8
ORDER BY win_pct DESC, five_setters DESC;

-- Q7. Longest winning streaks (gaps-and-islands with window functions).
WITH ordered AS (
    SELECT team, match_date, match_id, won,
           ROW_NUMBER() OVER (PARTITION BY team ORDER BY match_date, match_id)
         - ROW_NUMBER() OVER (PARTITION BY team, won ORDER BY match_date, match_id) AS grp
    FROM v_team_match
)
SELECT team, COUNT(*) AS consecutive_wins, MIN(match_date) AS from_date, MAX(match_date) AS to_date
FROM ordered
WHERE won = 1
GROUP BY team, grp
ORDER BY consecutive_wins DESC
LIMIT 10;

-- Q8. Set trends: are later sets tighter?
SELECT set_no,
       SUM(sets_played) AS sets_played,
       ROUND(SUM(avg_margin * sets_played) / SUM(sets_played), 2)        AS avg_margin,
       ROUND(SUM(close_set_pct * sets_played) / SUM(sets_played), 1)     AS close_set_pct,
       ROUND(SUM(extra_points_pct * sets_played) / SUM(sets_played), 1)  AS extra_points_pct
FROM v_set_trends
GROUP BY set_no
ORDER BY set_no;

-- Q9. Data quality: why were rows rejected or flagged?
SELECT severity, reason_code, COUNT(*) AS n
FROM etl_reject
GROUP BY severity, reason_code
ORDER BY severity, n DESC;

-- Q10. Reconciliation with the federation's published standings.
SELECT COUNT(*)            AS rows_checked,
       SUM(results_match)  AS results_identical,
       SUM(points_match)   AS points_identical,
       SUM(rank_match)     AS rank_identical
FROM v_standings_reconciliation;
