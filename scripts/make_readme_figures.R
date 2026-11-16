# Renders the charts used in the README from the pipeline database.
# Run from the repository root:  Rscript scripts/make_readme_figures.R
library(DBI)
source(file.path("shiny", "R", "plots.R"))

con <- dbConnect(RSQLite::SQLite(), file.path("build", "volleyball.db"))
dir.create(file.path("docs", "img"), recursive = TRUE, showWarnings = FALSE)
save <- function(name, plot, w = 8, h = 4.2) {
  ggsave(file.path("docs", "img", name), plot, width = w, height = h, dpi = 130, bg = "white")
}

season <- "2025/2026"   # last complete season in the sample data
save("points_race.png", plot_points_race(dbGetQuery(con,
  "SELECT team, match_seq, cumulative_points FROM v_team_form WHERE season = ? AND league_code = 'MEN_DN'",
  params = list(season))) + labs(subtitle = paste("MIXVOIP League Men", season, "- cumulative league points")))

save("home_away.png", plot_home_away(dbGetQuery(con,
  "SELECT team, venue_type, played, win_pct FROM v_home_away WHERE season = ? AND league_code = 'MEN_DN'",
  params = list(season))), h = 4.6)

save("home_advantage.png", plot_home_advantage(dbGetQuery(con,
  "SELECT season, league_code, league_name, home_win_pct FROM v_league_home_advantage ORDER BY season")))

save("set_margins.png", plot_margin_distribution(dbGetQuery(con, "SELECT set_no, margin FROM v_set")))

dbDisconnect(con)
