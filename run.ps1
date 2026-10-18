$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    Write-Error "Java 17+ is required but javac was not found in PATH."
    exit 1
}

if (Test-Path out) {
    Remove-Item -Recurse -Force out
}
New-Item -ItemType Directory -Force -Path out | Out-Null

$files = Get-ChildItem -Path src -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
if (-not $files) {
    Write-Error "No Java source files were found under src/."
    exit 1
}

javac -d out $files
java -cp out com.fatshe.movers.Main
