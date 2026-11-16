# ---------------------------------------------------------------------------
# FLVB volleyball explorer - R Shiny app
#
# Reads the SQLite database built by the Java ETL (build/volleyball.db).
# All business logic lives in the SQL views (sql/02_views.sql); this app only
# filters those views and draws them, so Shiny and Power BI always agree.
#
# Run from the repository root:   Rscript -e "shiny::runApp('shiny')"
# ---------------------------------------------------------------------------

library(shiny)
library(bslib)
library(DBI)
library(DT)
# R/plots.R is sourced automatically by Shiny (chart functions and palette).

# --- data access -----------------------------------------------------------

DB_PATH <- Sys.getenv("FLVB_DB", unset = file.path("..", "build", "volleyball.db"))
if (!file.exists(DB_PATH)) {
  stop("Database not found at '", DB_PATH, "'. Run the ETL first (see README) ",
       "or set the FLVB_DB environment variable.", call. = FALSE)
}
con <- dbConnect(RSQLite::SQLite(), DB_PATH, flags = RSQLite::SQLITE_RO)   # read-only
onStop(function() dbDisconnect(con))

# Parameterised query helper: values are always bound, never pasted into SQL.
q <- function(sql, ...) {
  params <- unname(list(...))
  if (length(params) == 0) dbGetQuery(con, sql) else dbGetQuery(con, sql, params = params)
}

seasons <- q("SELECT season_label FROM season ORDER BY start_year DESC")$season_label
leagues <- q("SELECT league_code, league_name FROM league ORDER BY tier")
league_choices <- setNames(leagues$league_code, leagues$league_name)
run_info <- q("SELECT finished_at, matches_loaded FROM etl_run ORDER BY run_id DESC LIMIT 1")

# --- UI --------------------------------------------------------------------

kpi <- function(id, label) {
  value_box(title = label, value = textOutput(id, inline = TRUE), theme = "light", height = "105px")
}

ui <- page_navbar(
  title = "FLVB Men's Volleyball",
  theme = bs_theme(version = 5, primary = "#2a78d6", "font-size-base" = "0.95rem"),
  fillable = FALSE,
  sidebar = sidebar(
    width = 270,
    selectInput("season", "Season", seasons),
    selectInput("league", "Division", league_choices),
    selectInput("team", "Team", choices = NULL),
    helpText("Season and division apply to every tab; the team applies to the Team tab."),
    hr(),
    tags$small(class = "text-muted",
               sprintf("%s matches loaded, last ETL run %s.", run_info$matches_loaded, run_info$finished_at),
               tags$br(), "Source: flvb.lu public results.")
  ),

  nav_panel("Rankings",
    card(card_header("Standings"), DTOutput("standings")),
    card(card_header("Points race"),
         selectizeInput("race_teams", NULL, choices = NULL, multiple = TRUE, width = "100%",
                        options = list(placeholder = "Highlight teams (default: all, max 8)", maxItems = 8)),
         plotOutput("points_race", height = 380))
  ),

  nav_panel("Team",
    layout_columns(fill = FALSE,
      kpi("kpi_played", "Matches played"), kpi("kpi_win", "Win rate"),
      kpi("kpi_sets", "Sets won : lost"), kpi("kpi_ppm", "League points per match")),
    layout_columns(
      card(plotOutput("score_breakdown", height = 300)),
      card(plotOutput("team_sets", height = 300))),
    card(checkboxInput("form_all_seasons", "Show all seasons", FALSE),
         plotOutput("team_form", height = 300)),
    card(card_header("Matches"), DTOutput("team_matches"))
  ),

  nav_panel("Home / Away",
    card(plotOutput("home_away", height = 420)),
    card(plotOutput("home_advantage", height = 340),
         card_footer(class = "text-muted small", "All seasons and divisions, independent of the filters."))
  ),

  nav_panel("Set trends",
    radioButtons("set_scope", NULL, inline = TRUE,
                 choices = c("Selected season and division" = "selection", "All seasons, selected division" = "league",
                             "Everything" = "all")),
    layout_columns(
      card(plotOutput("set_margin", height = 300)),
      card(plotOutput("set_close", height = 300))),
    card(plotOutput("margin_dist", height = 320))
  ),

  nav_panel("Data quality",
    layout_columns(fill = FALSE,
      kpi("dq_raw", "Raw rows read"), kpi("dq_loaded", "Matches loaded"),
      kpi("dq_rejected", "Rows rejected"), kpi("dq_recon", "Standings rows reconciled")),
    card(card_header("Rejected and flagged rows by reason"), DTOutput("dq_reasons")),
    card(card_header("Computed vs. official standings - differences"), DTOutput("dq_recon_table"))
  )
)

# --- server ----------------------------------------------------------------

server <- function(input, output, session) {

  table_opts <- list(dom = "t", paging = FALSE, ordering = TRUE)

  standings <- reactive({
    q("SELECT rank, team, played, won, lost, w_3_0, w_3_1, w_3_2, l_2_3, l_1_3, l_0_3, points,
              sets_won, sets_lost, set_ratio, balls_won, balls_lost, ball_ratio, win_pct
       FROM v_standings WHERE season = ? AND league_code = ? ORDER BY rank", input$season, input$league)
  })

  # Teams offered in the selectors follow the season / division filter.
  observe({
    teams <- standings()$team
    keep <- if (isTRUE(input$team %in% teams)) input$team else teams[1]
    updateSelectInput(session, "team", choices = teams, selected = keep)
    updateSelectizeInput(session, "race_teams", choices = teams, selected = character(0))
  })

  # ---- Rankings
  output$standings <- renderDT({
    d <- standings()
    names(d) <- c("#", "Team", "P", "W", "L", "3-0", "3-1", "3-2", "2-3", "1-3", "0-3", "Pts",
                  "Sets +", "Sets -", "Set ratio", "Balls +", "Balls -", "Ball ratio", "Win %")
    datatable(d, rownames = FALSE, options = table_opts, class = "compact hover") |>
      formatStyle("Pts", fontWeight = "bold")
  })

  output$points_race <- renderPlot({
    form <- q("SELECT team, match_seq, cumulative_points FROM v_team_form WHERE season = ? AND league_code = ?",
              input$season, input$league)
    plot_points_race(form, input$race_teams)
  }, res = 96)

  # ---- Team
  team_matches <- reactive({
    req(input$team)
    q("SELECT match_date, venue_type, opponent, won, score, points_for, points_against, league_points, duration_min, is_forfeit
       FROM v_team_match WHERE season = ? AND league_code = ? AND team = ? ORDER BY match_date",
      input$season, input$league, input$team)
  })

  output$kpi_played <- renderText(nrow(team_matches()))
  output$kpi_win <- renderText({
    d <- team_matches(); if (nrow(d) == 0) "-" else sprintf("%.0f%%", 100 * mean(d$won))
  })
  output$kpi_sets <- renderText({
    s <- standings(); s <- s[s$team == input$team, ]
    if (nrow(s) == 0) "-" else paste(s$sets_won, ":", s$sets_lost)
  })
  output$kpi_ppm <- renderText({
    d <- team_matches(); if (nrow(d) == 0) "-" else sprintf("%.2f", mean(d$league_points))
  })

  output$score_breakdown <- renderPlot(plot_score_breakdown(team_matches()), res = 96)

  output$team_sets <- renderPlot({
    req(input$team)
    profile <- q("SELECT set_no, sets_played, set_win_pct FROM v_team_set_profile
                  WHERE season = ? AND league_code = ? AND team = ? ORDER BY set_no",
                 input$season, input$league, input$team)
    plot_team_set_profile(profile, input$team)
  }, res = 96)

  output$team_form <- renderPlot({
    req(input$team)
    form <- if (isTRUE(input$form_all_seasons)) {
      q("SELECT season, match_date, won, last5_win_pct FROM v_team_form WHERE team = ?", input$team)
    } else {
      q("SELECT season, match_date, won, last5_win_pct FROM v_team_form WHERE season = ? AND league_code = ? AND team = ?",
        input$season, input$league, input$team)
    }
    plot_team_form(form)
  }, res = 96)

  output$team_matches <- renderDT({
    d <- team_matches()
    d$won <- ifelse(d$won == 1, "Won", "Lost")
    d$is_forfeit <- ifelse(d$is_forfeit == 1, "Yes", "")
    names(d) <- c("Date", "Venue", "Opponent", "Result", "Sets", "Points for", "Points against",
                  "League pts", "Duration (min)", "Forfeit")
    datatable(d, rownames = FALSE, options = table_opts, class = "compact hover")
  })

  # ---- Home / Away
  output$home_away <- renderPlot({
    plot_home_away(q("SELECT team, venue_type, played, win_pct FROM v_home_away WHERE season = ? AND league_code = ?",
                     input$season, input$league))
  }, res = 96)

  output$home_advantage <- renderPlot({
    plot_home_advantage(q("SELECT season, league_code, league_name, home_win_pct FROM v_league_home_advantage ORDER BY season"))
  }, res = 96)

  # ---- Set trends
  sets <- reactive({
    switch(input$set_scope,
      selection = q("SELECT set_no, margin, went_to_extra_points FROM v_set WHERE season = ? AND league_code = ?",
                    input$season, input$league),
      league = q("SELECT set_no, margin, went_to_extra_points FROM v_set WHERE league_code = ?", input$league),
      all = q("SELECT set_no, margin, went_to_extra_points FROM v_set"))
  })

  output$set_margin <- renderPlot({
    d <- sets(); if (nrow(d) == 0) return(plot_empty())
    plot_set_metric(aggregate(cbind(value = margin) ~ set_no, d, mean),
                    "Average winning margin by set", "Points between winner and loser of the set", " pts")
  }, res = 96)

  output$set_close <- renderPlot({
    d <- sets(); if (nrow(d) == 0) return(plot_empty())
    d$close <- 100 * (d$margin == 2)
    plot_set_metric(aggregate(cbind(value = close) ~ set_no, d, mean),
                    "Share of sets decided by two points", "The minimum possible margin", "%")
  }, res = 96)

  output$margin_dist <- renderPlot(plot_margin_distribution(sets()), res = 96)

  # ---- Data quality
  dq <- q("SELECT * FROM v_dq_summary")
  output$dq_raw <- renderText(dq$raw_rows)
  output$dq_loaded <- renderText(dq$matches)
  output$dq_rejected <- renderText(dq$rejected_rows)
  output$dq_recon <- renderText(paste(dq$standings_rows_reconciled, "/", dq$standings_rows_checked))

  output$dq_reasons <- renderDT({
    d <- q("SELECT severity, reason_code, COUNT(*) AS n, MIN(detail) AS example
            FROM etl_reject GROUP BY severity, reason_code ORDER BY severity, n DESC")
    names(d) <- c("Severity", "Reason", "Rows", "Example")
    datatable(d, rownames = FALSE, options = table_opts, class = "compact hover")
  })

  output$dq_recon_table <- renderDT({
    d <- q("SELECT season, league_code, team, official_played, computed_played, official_points, computed_points,
                   official_rank, computed_rank
            FROM v_standings_reconciliation WHERE results_match = 0 OR points_match = 0 OR rank_match = 0")
    names(d) <- c("Season", "Division", "Team", "Played (official)", "Played (computed)",
                  "Points (official)", "Points (computed)", "Rank (official)", "Rank (computed)")
    datatable(d, rownames = FALSE, options = table_opts, class = "compact hover")
  })
}

shinyApp(ui, server)
