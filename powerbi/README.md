# Power BI reporting layer

`powerbi/data/` is a ready-to-load **star schema** exported by the Java ETL
from the SQL views. No Power Query transformations are needed: types are
clean, keys are consistent, and the business rules were already applied in SQL.

| File | Grain | Rows | Role |
|---|---|---|---|
| `fact_team_match.csv` | one row per team per played match | 1,900 | main fact: wins, sets, points, league points |
| `fact_match.csv` | one row per match (played and scheduled) | 1,131 | fixtures, scores, durations |
| `fact_set.csv` | one row per set played | 3,386 | set-level analysis |
| `standings.csv` | one row per team, season and division | 167 | pre-computed league table with rank |
| `dim_team.csv` | team | 38 | club, squad number |
| `dim_season.csv` | season | 6 | |
| `dim_league.csv` | division | 4 | name and tier |
| `dim_date.csv` | calendar day | 2,069 | continuous calendar with season, month, weekday |

> Prefer a live connection? Install the SQLite ODBC driver and connect to
> `build/volleyball.db`, selecting the views `v_team_match`, `v_match`,
> `v_set`, `v_standings`. The CSV route below needs no driver.

## 1. Load the data

1. Power BI Desktop -> **Get data -> Folder** -> select `powerbi/data` -> **Transform data**.
   (Or **Get data -> Text/CSV** once per file.)
2. Load each file as its own table. Check the detected types:
   `match_date` / `date` = Date, ids and counts = Whole number, ratios = Decimal number,
   `season` and `league_code` = Text.
3. **View -> Themes -> Browse for themes** -> `powerbi/theme.json` (same colours as the Shiny app).

## 2. Model (Model view)

Create these one-to-many relationships, single direction, dimension -> fact:

| From (one) | To (many) |
|---|---|
| `dim_team[team_id]` | `fact_team_match[team_id]` |
| `dim_team[team_id]` | `standings[team_id]` |
| `dim_season[season]` | `fact_team_match[season]`, `fact_match[season]`, `fact_set[season]`, `standings[season]` |
| `dim_league[league_code]` | `fact_team_match[league_code]`, `fact_match[league_code]`, `fact_set[league_code]`, `standings[league_code]` |
| `dim_date[date]` | `fact_team_match[match_date]`, `fact_match[match_date]` |

Then: mark `dim_date` as the **date table** (column `date`); sort `dim_league[league_name]`
by `tier` and `dim_date[month_name]` by `month_no`; hide the key columns on the fact tables.

`fact_match` holds two team roles (home and away). Keep it unrelated to `dim_team` and
use `fact_team_match` for anything sliced by team - that is why the unpivoted fact exists.

## 3. Measures

Create a table named `Measures` (Enter data, one dummy column) and add:

```DAX
Matches Played = COUNTROWS ( fact_team_match )

Wins = CALCULATE ( [Matches Played], fact_team_match[won] = 1 )

Losses = [Matches Played] - [Wins]

Win % = DIVIDE ( [Wins], [Matches Played] )

League Points = SUM ( fact_team_match[league_points] )

Points per Match = DIVIDE ( [League Points], [Matches Played] )

Sets Won = SUM ( fact_team_match[sets_for] )

Sets Lost = SUM ( fact_team_match[sets_against] )

Set Ratio = DIVIDE ( [Sets Won], [Sets Lost] )

Ball Ratio =
DIVIDE ( SUM ( fact_team_match[points_for] ), SUM ( fact_team_match[points_against] ) )

Home Win % =
CALCULATE ( [Win %], fact_team_match[venue_type] = "Home", fact_team_match[is_forfeit] = 0 )

Away Win % =
CALCULATE ( [Win %], fact_team_match[venue_type] = "Away", fact_team_match[is_forfeit] = 0 )

Home Boost (pp) = ( [Home Win %] - [Away Win %] ) * 100

-- Rank inside the current season / division filter context
Rank =
IF (
    HASONEVALUE ( dim_team[team_name] ),
    RANKX (
        ALLSELECTED ( dim_team[team_name] ),
        [League Points] + [Set Ratio] / 1000,
        ,
        DESC,
        DENSE
    )
)

-- Set-level measures (fact_set)
Sets Played = COUNTROWS ( fact_set )

Avg Set Margin = AVERAGE ( fact_set[margin] )

Close Set % =
DIVIDE ( CALCULATE ( [Sets Played], fact_set[margin] = 2 ), [Sets Played] )

Extra Points Set % =
DIVIDE ( CALCULATE ( [Sets Played], fact_set[went_to_extra_points] = 1 ), [Sets Played] )

Five-Set Matches =
CALCULATE ( [Matches Played], fact_team_match[sets_for] + fact_team_match[sets_against] = 5 )

Avg Match Duration (min) = AVERAGE ( fact_match[duration_min] )
```

Format `Win %`, `Home Win %`, `Away Win %`, `Close Set %`, `Extra Points Set %` as percentages.

## 4. Report pages

Put two slicers on every page (sync them: **View -> Sync slicers**):
`dim_season[season]` (dropdown, single select, default latest) and `dim_league[league_name]`.

**Page 1 - League overview**
- Cards: `Played Matches = CALCULATE ( COUNTROWS ( fact_match ), fact_match[status] = "played" )`, `Avg Match Duration (min)` and `Close Set %`.
- Table (the league table): from `standings` - `rank`, `team`, `played`, `won`, `lost`, `points`, `sets_won`, `sets_lost`, `set_ratio`, `ball_ratio`; sort by `rank`; data bars on `points`.
- Line chart (points race): axis `dim_date[date]`, legend `dim_team[team_name]`, values a running total
  `Cumulative Points = CALCULATE ( [League Points], FILTER ( ALLSELECTED ( dim_date[date] ), dim_date[date] <= MAX ( dim_date[date] ) ) )`.

**Page 2 - Team performance** (add a `dim_team[team_name]` slicer)
- Cards: `Win %`, `Points per Match`, `Set Ratio`, `Five-Set Matches`.
- Clustered column: axis `fact_team_match[score]` (sort 3-0 ... 0-3), value `Matches Played`.
- Line chart: axis `dim_date[year_month]`, value `Win %`.
- Table: `match_date`, `opponent`, `venue_type`, `score`, `league_points`.

**Page 3 - Home vs away**
- Clustered bar: axis `dim_team[team_name]`, values `Home Win %` and `Away Win %`.
- Line chart: axis `dim_season[season]`, legend `dim_league[league_name]`, value `Home Win %` (remove the slicer interaction on this visual).
- Card: `Home Boost (pp)`.

**Page 4 - Set trends**
- Column chart: axis `fact_set[set_no]`, value `Avg Set Margin`.
- Column chart: axis `fact_set[set_no]`, value `Close Set %`.
- Histogram: axis `fact_set[margin]`, value `Sets Played`; small multiples by a calculated column
  `Set Type = IF ( fact_set[set_no] = 5, "Tie-break", "Sets 1-4" )`.

## 5. Check your numbers

After building, these should match the Shiny app and `sql/03_analysis_queries.sql`
(all seasons and divisions selected):

| Measure | Expected |
|---|---|
| Played matches (`fact_match`, status = played) | 950 |
| `Sets Played` | 3,386 |
| `Home Win %` (all divisions) | 54.6 % |
| `Close Set %` for set 5 | 25.5 % |
| 2025/2026 MIXVOIP League winner | VC Stroossen 1, 38 points |
