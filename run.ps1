# Builds the database and the Power BI dataset, then starts the Shiny app.
# Usage: .\run.ps1          (ETL + tests + app)
#        .\run.ps1 etl      (ETL only)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Push-Location etl
try {
    mvn -q test
    if ($LASTEXITCODE -ne 0) { throw "Tests failed" }
    mvn -q compile exec:java
    if ($LASTEXITCODE -ne 0) { throw "ETL failed" }
} finally {
    Pop-Location
}

if ($args[0] -ne "etl") {
    Rscript -e "shiny::runApp('shiny', launch.browser = TRUE)"
}
