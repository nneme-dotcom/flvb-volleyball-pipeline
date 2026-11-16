# Data source

**Source:** public results pages of the Fédération Luxembourgeoise de Volleyball,
<https://flvb.lu/indoor/results/championship/championship-men-matches-results>

**Scope:** men's championship, 4 divisions (MIXVOIP League, Division 1-3),
seasons 2021/2022 to 2026/2027. Snapshot taken on 2026-11-16, so 2026/2027 is
in progress and contains scheduled fixtures as well as results.

| File | Rows | Content |
|---|---|---|
| `data/raw/flvb_men_matches.csv` | 1,254 | one row per match as listed on the "All matches" tab |
| `data/raw/flvb_men_standings_official.csv` | 184 | the published standings tables (used only for reconciliation) |
| `data/reference/clubs.csv` | 15 | club abbreviation -> club name (maintained by hand) |
| `data/reference/leagues.csv` | 4 | division code -> name and tier (maintained by hand) |

## How the snapshot was taken

The page is a plain HTML form (`?l266=<division>&s266=<season>`). The raw files
were produced by reading the result tables for every division and season and
writing the cells out **unchanged**: dates stay `dd.mm.yy`, results stay `3:1`,
set scores stay `25:20 25:22 21:25 25:20`, team names keep whatever spelling
the site used that season. All cleaning happens in the Java ETL, on purpose,
so the pipeline has real work to do and every decision is visible in code.

## Raw match file

| Column | Example | Notes |
|---|---|---|
| `season` | `2025/2026` | |
| `league` | `MEN_DN` | division code used by the site |
| `date` | `24.10.26` | `dd.mm.yy`; one row contains the text `Postponed` |
| `time` | `20:00` | `00:00` = not fixed yet |
| `match_no` | `12` | number within season and division |
| `home_team`, `away_team` | `VCS Hommes 1`, `VC Stroossen 1` | naming convention changed over the years |
| `result` | `3:0` | empty for matches not played yet |
| `set_scores` | `25:19 25:14 25:11` | home:away per set |
| `duration_min` | `92` | sometimes missing, one outlier (18,885) |
| `venue` | `Hall Sportif Belair (Terrain Central)` | only shown by the site for upcoming matches |
| `match_uuid` | `c83b347f-…` | match id of the federation's system (natural key) |

## Not included (yet)

Each played match also has a statistical match report (serve, reception,
attack and block figures per player and per set). Parsing those reports into
a `player_match_stats` table is the natural next step - see the roadmap in
the README.
