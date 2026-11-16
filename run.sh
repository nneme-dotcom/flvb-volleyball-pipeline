#!/usr/bin/env bash
# Builds the database and the Power BI dataset, then starts the Shiny app.
# Usage: ./run.sh          (ETL + tests + app)
#        ./run.sh etl      (ETL only)
set -euo pipefail
cd "$(dirname "$0")"

(cd etl && mvn -q test && mvn -q compile exec:java)

if [ "${1:-}" != "etl" ]; then
  Rscript -e "shiny::runApp('shiny', launch.browser = TRUE)"
fi
