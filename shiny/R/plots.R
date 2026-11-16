# ---------------------------------------------------------------------------
# plots.R - chart functions shared by the Shiny app and the README figures.
#
# Every function takes a plain data frame (already aggregated in SQL) and
# returns a ggplot object, so charts can be tested and reused outside Shiny.
# ---------------------------------------------------------------------------

library(ggplot2)

# Categorical palette, used in this fixed order (colour-blind safe as a set).
PALETTE <- c("#2a78d6", "#eb6834", "#1baf7a", "#eda100",
             "#e87ba4", "#008300", "#6250d6", "#e34948")
COL_HOME <- PALETTE[1]
COL_AWAY <- PALETTE[2]
COL_MUTED <- "#b5b4ae"
INK <- "#0b0b0b"
INK_SOFT <- "#52514e"

# Quiet theme: horizontal grid only, no chart junk, readable at dashboard size.
theme_volley <- function(base_size = 13) {
  theme_minimal(base_size = base_size) +
    theme(
      text = element_text(colour = INK),
      plot.title = element_text(face = "bold", size = rel(1.05)),
      plot.subtitle = element_text(colour = INK_SOFT, margin = margin(b = 10)),
      plot.title.position = "plot",
      axis.title = element_text(colour = INK_SOFT, size = rel(0.85)),
      axis.text = element_text(colour = INK_SOFT),
      panel.grid.major.x = element_blank(),
      panel.grid.minor = element_blank(),
      panel.grid.major.y = element_line(colour = "#e7e6e1", linewidth = 0.4),
      legend.position = "top",
      legend.justification = "left",
      legend.title = element_blank(),
      plot.margin = margin(12, 16, 8, 12)
    )
}

# Message shown instead of a chart when a filter combination has no data.
plot_empty <- function(message = "No played matches for this selection") {
  ggplot() +
    annotate("text", x = 0, y = 0, label = message, colour = INK_SOFT, size = 5) +
    theme_void()
}

# Points race: cumulative league points after each match, one line per team.
# `form` = rows of v_team_form for one season and division.
# Up to 8 teams get their own colour (assigned alphabetically so a team keeps
# its colour whatever the standings are); `highlight` narrows that down.
plot_points_race <- function(form, highlight = NULL) {
  if (nrow(form) == 0) return(plot_empty())
  teams <- sort(unique(form$team))
  if (is.null(highlight) || length(highlight) == 0) highlight <- teams
  highlight <- head(intersect(teams, highlight), length(PALETTE))
  colours <- setNames(PALETTE[seq_along(highlight)], highlight)

  front <- form[form$team %in% highlight, ]
  back <- form[!form$team %in% highlight, ]
  ends <- front[front$match_seq == ave(front$match_seq, front$team, FUN = max), ]   # last point of each line

  ggplot(front, aes(match_seq, cumulative_points, group = team)) +
    geom_line(data = back, colour = COL_MUTED, linewidth = 0.6, alpha = 0.7) +
    geom_line(aes(colour = team), linewidth = 0.9) +
    geom_point(data = ends, aes(colour = team), size = 2.6) +
    scale_colour_manual(values = colours) +
    scale_x_continuous(breaks = function(l) seq(1, max(l), by = max(1, round(max(l) / 7)))) +
    labs(title = "Points race", subtitle = "Cumulative league points after each match played",
         x = "Matches played", y = "League points") +
    theme_volley()
}

# Home vs away win rate per team (dumbbell-style grouped bars).
# `ha` = rows of v_home_away for one season and division.
plot_home_away <- function(ha) {
  if (nrow(ha) == 0) return(plot_empty())
  home <- ha[ha$venue_type == "Home", ]
  ha$team <- factor(ha$team, levels = home$team[order(home$win_pct)])
  ha <- ha[!is.na(ha$team), ]
  ggplot(ha, aes(win_pct, team, fill = venue_type)) +
    geom_col(position = position_dodge(width = 0.75), width = 0.65) +
    geom_text(aes(label = sprintf("%.0f%%", win_pct)), position = position_dodge(width = 0.75),
              hjust = -0.15, size = 3.4, colour = INK_SOFT) +
    scale_fill_manual(values = c(Home = COL_HOME, Away = COL_AWAY), breaks = c("Home", "Away")) +
    scale_x_continuous(limits = c(0, 112), breaks = seq(0, 100, 25), labels = function(x) paste0(x, "%")) +
    labs(title = "Win rate at home and away", subtitle = "Share of matches won, forfeits excluded",
         x = NULL, y = NULL) +
    theme_volley() +
    theme(panel.grid.major.y = element_blank(),
          panel.grid.major.x = element_line(colour = "#e7e6e1", linewidth = 0.4))
}

# Home win rate by season, one line per division.
# `adv` = rows of v_league_home_advantage.
plot_home_advantage <- function(adv) {
  if (nrow(adv) == 0) return(plot_empty())
  leagues <- unique(adv$league_name[order(adv$league_code != "MEN_DN", adv$league_code)])
  adv$league_name <- factor(adv$league_name, levels = leagues)
  ggplot(adv, aes(season, home_win_pct, colour = league_name, group = league_name)) +
    geom_hline(yintercept = 50, colour = INK_SOFT, linetype = "dashed", linewidth = 0.4) +
    geom_line(linewidth = 0.9) +
    geom_point(size = 2.6) +
    scale_colour_manual(values = setNames(PALETTE[seq_along(leagues)], leagues)) +
    scale_y_continuous(labels = function(x) paste0(x, "%")) +
    labs(title = "Home-court advantage by season",
         subtitle = "Share of matches won by the home team (dashed line = no advantage)",
         x = NULL, y = "Home win rate") +
    theme_volley()
}

# One metric of v_set_trends by set number, pooled over the selection.
# `trends` needs columns set_no and the weighted metric `value`.
plot_set_metric <- function(trends, title, subtitle, suffix = "") {
  if (nrow(trends) == 0) return(plot_empty())
  ggplot(trends, aes(factor(set_no), value)) +
    geom_col(fill = COL_HOME, width = 0.6) +
    geom_text(aes(label = paste0(format(round(value, 1), nsmall = 1), suffix)),
              vjust = -0.5, size = 3.6, colour = INK_SOFT) +
    scale_y_continuous(expand = expansion(mult = c(0, 0.15))) +
    labs(title = title, subtitle = subtitle, x = "Set", y = NULL) +
    theme_volley()
}

# Distribution of winning margins, tie-break shown separately because it is
# played to 15 instead of 25. `sets` = rows of v_set.
plot_margin_distribution <- function(sets) {
  if (nrow(sets) == 0) return(plot_empty())
  sets$kind <- ifelse(sets$set_no == 5, "Tie-break (to 15)", "Sets 1-4 (to 25)")
  ggplot(sets, aes(margin)) +
    geom_bar(fill = COL_HOME, width = 0.8) +
    facet_wrap(~kind, scales = "free_y") +
    scale_x_continuous(breaks = seq(2, 24, 4)) +
    labs(title = "How close are sets?", subtitle = "Number of sets by winning margin in points",
         x = "Winning margin (points)", y = "Sets") +
    theme_volley() +
    theme(strip.text = element_text(hjust = 0, face = "bold", colour = INK_SOFT))
}

# Set win rate by set number for one team against the league average (50%).
# `profile` = rows of v_team_set_profile for one team.
plot_team_set_profile <- function(profile, team) {
  if (nrow(profile) == 0) return(plot_empty())
  ggplot(profile, aes(factor(set_no), set_win_pct)) +
    geom_col(fill = COL_HOME, width = 0.6) +
    geom_hline(yintercept = 50, colour = INK_SOFT, linetype = "dashed", linewidth = 0.4) +
    geom_text(aes(label = sprintf("%.0f%%\n(%d sets)", set_win_pct, sets_played)),
              vjust = -0.2, size = 3.3, colour = INK_SOFT, lineheight = 0.9) +
    scale_y_continuous(limits = c(0, 118), breaks = seq(0, 100, 25), labels = function(x) paste0(x, "%")) +
    labs(title = paste("Set win rate by set number -", team),
         subtitle = "Strong starter or strong finisher? Dashed line = 50%", x = "Set", y = NULL) +
    theme_volley()
}

# Match outcomes of one team by exact score (3-0 ... 0-3), wins and losses.
# `tm` = rows of v_team_match for one team.
plot_score_breakdown <- function(tm) {
  if (nrow(tm) == 0) return(plot_empty())
  levels <- c("3-0", "3-1", "3-2", "2-3", "1-3", "0-3")
  counts <- as.data.frame(table(score = factor(tm$score, levels = levels)))
  counts$outcome <- ifelse(counts$score %in% levels[1:3], "Won", "Lost")
  ggplot(counts, aes(score, Freq, fill = outcome)) +
    geom_col(width = 0.65) +
    geom_text(aes(label = Freq), vjust = -0.5, size = 3.6, colour = INK_SOFT) +
    scale_fill_manual(values = c(Won = COL_HOME, Lost = COL_AWAY), breaks = c("Won", "Lost")) +
    scale_y_continuous(expand = expansion(mult = c(0, 0.15))) +
    labs(title = "Results by score", subtitle = "Number of matches by final score in sets", x = NULL, y = NULL) +
    theme_volley()
}

# Rolling form: share of the last five matches won.
# `form` = rows of v_team_form for one team (one or more seasons).
plot_team_form <- function(form) {
  if (nrow(form) == 0) return(plot_empty())
  form$match_date <- as.Date(form$match_date)
  ggplot(form, aes(match_date, last5_win_pct, group = season)) +
    geom_hline(yintercept = 50, colour = INK_SOFT, linetype = "dashed", linewidth = 0.4) +
    geom_line(colour = COL_HOME, linewidth = 0.9) +
    geom_point(aes(shape = ifelse(won == 1, "Won", "Lost")), colour = COL_HOME, fill = "white", size = 2.4, stroke = 0.9) +
    scale_shape_manual(values = c(Won = 19, Lost = 21), breaks = c("Won", "Lost")) +
    scale_y_continuous(limits = c(0, 100), labels = function(x) paste0(x, "%")) +
    labs(title = "Form", subtitle = "Win rate over the last five matches; each dot is one match",
         x = NULL, y = NULL) +
    theme_volley()
}
