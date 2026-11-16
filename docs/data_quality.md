# Data quality

The pipeline never drops a row silently. Every source row ends up either in
`match` or in `etl_reject` with a reason code, and the integration test
asserts `rows read = matches loaded + rows rejected`.

## What the sample data contains

| Severity | Reason | Rows | What happened | Handling |
|---|---|---|---|---|
| REJECT | `DUPLICATE_MATCH` | 112 | Division 3 did not exist in 2021/22 and 2022/23; for those seasons the site shows the top-division matches again under the Division 3 code | Match id is the natural key: first occurrence kept |
| REJECT | `UNKNOWN_TEAM` | 10 | Fixtures against a placeholder entry (`#6, Meldeliste`) for a team that never registered | Rejected: not a real match |
| REJECT | `INVALID_DATE` | 1 | A played match whose date cell contains the text `Postponed` | Rejected: `match_date` is mandatory |
| WARNING | `STANDINGS_WITHOUT_MATCHES` | 16 | Standings rows of the two duplicated Division 3 seasons | Not loaded |
| WARNING | `DURATION_OUT_OF_RANGE` | 1 | Match duration of 18,885 minutes | Match kept, duration set to NULL |

Cleaning that does not produce a reject:

- **Team identity.** `VCS Hommes`, `VCS Hommes 1`, `VCS 3 Hommes 3`, `VCS  Hommes 3`
  and `VC Stroossen 1` are five spellings found in the source; all resolve to
  club `VCS` plus a squad number (`TeamNameNormalizer`). 81 raw team names become 38 teams.
- **Forfeits.** 14 matches awarded 25:0 25:0 25:0 are flagged `is_forfeit`.
  They count in the standings but are excluded from set and home/away analysis.
- **Placeholders.** Time `00:00` (not fixed yet) becomes NULL.
- **32 played matches have no duration** in the source; they stay NULL.

## Reconciliation with the published standings

`v_standings_reconciliation` compares the standings computed from match rows
with the tables published by the federation (168 team-season rows).

| Check | Identical |
|---|---|
| Matches, wins, sets and rally points | 166 / 168 |
| League points | 166 / 168 |
| Rank | 168 / 168 |

The three differing rows are all explained:

| Season | Division | Team | Difference | Cause |
|---|---|---|---|---|
| 2024/2025 | Division 3 | Escher VBC 4, CHEV Diekirch 3 | one match fewer than the official table | their match is the `Postponed` row rejected above |
| 2022/2023 | MIXVOIP League | Escher VBC 1 | 0 points officially, 1 computed | results are identical; the team forfeited a match that season, so the gap is most likely a points penalty, which cannot be derived from scores |

## Constraints enforced by the database

Besides primary and foreign keys: a played match must have exactly one side
with three sets, a scheduled match must have no score, home and away team
must differ, set numbers are 1-5, a set cannot be drawn, match numbers are
unique within season and division, durations are 1-300 minutes. See
`sql/01_schema.sql`.
