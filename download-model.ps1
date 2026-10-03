$ErrorActionPreference = 'Stop'
$assets = Join-Path $PSScriptRoot 'app\src\main\assets'
New-Item -ItemType Directory -Force $assets | Out-Null
@(
    @{ Name = 'ru'; Version = 'vosk-model-small-ru-0.22'; UUID = 'ru-small-0.22-v1' },
    @{ Name = 'en'; Version = 'vosk-model-small-en-us-0.15'; UUID = 'en-small-0.15-v1' }
) | ForEach-Object {
    $zip = Join-Path $env:TEMP ($_.Version + '.zip')
    $model = Join-Path $assets ('model-' + $_.Name)
    if (-not (Test-Path $model)) {
        Invoke-WebRequest ('https://alphacephei.com/vosk/models/' + $_.Version + '.zip') -OutFile $zip
        Expand-Archive -LiteralPath $zip -DestinationPath $assets -Force
        Move-Item -LiteralPath (Join-Path $assets $_.Version) -Destination $model
    }
    Set-Content -LiteralPath (Join-Path $model 'uuid') -Value $_.UUID -Encoding ascii
}
