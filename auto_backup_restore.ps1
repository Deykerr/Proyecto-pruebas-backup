$BIN     = "C:\Program Files\PostgreSQL\18\bin"
$ARCHIVO = "C:\backups_tienda\tienda_backup.backup"
$LOG     = "C:\backups_tienda\log_auto.txt"
$env:PGPASSWORD = "root"

function Write-Log($msg) {
    $linea = "$(Get-Date -Format 'HH:mm:ss') - $msg"
    Add-Content -Path $LOG -Value $linea
    Write-Host $linea -ForegroundColor Cyan
}

Write-Host "========================================" -ForegroundColor Green
Write-Host "  Backup/Restore automatico - tienda   " -ForegroundColor Green
Write-Host "  Intervalo: 60 segundos               " -ForegroundColor Green
Write-Host "  Backup en: $ARCHIVO " -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green

Write-Log "=== Servicio iniciado ==="

while ($true) {
    $bdExiste = (& "$BIN\psql.exe" -U postgres -h localhost -p 5432 -lqt 2>$null) -match "tienda"

    if ($bdExiste) {
        # BD existe -> hacer backup
        & "$BIN\pg_dump.exe" -U postgres -h localhost -p 5432 -d tienda -F c -f "$ARCHIVO" 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Log "BACKUP OK -> tienda_backup.backup"
        } else {
            Write-Log "ERROR en backup (cod $LASTEXITCODE)"
        }
    } else {
        # BD NO existe -> restaurar
        Write-Log "BD tienda NO existe -> restaurando..."
        if (Test-Path $ARCHIVO) {
            & "$BIN\createdb.exe" -U postgres -h localhost -p 5432 tienda 2>$null
            & "$BIN\pg_restore.exe" -U postgres -h localhost -p 5432 -d tienda --no-owner --clean --if-exists "$ARCHIVO" 2>$null
            $check = (& "$BIN\psql.exe" -U postgres -h localhost -p 5432 -d tienda -c "SELECT 1" 2>$null)
            if ($LASTEXITCODE -eq 0) {
                Write-Log "RESTORE OK ✔"
            } else {
                Write-Log "ERROR en restore"
            }
        } else {
            Write-Log "ERROR: no existe $ARCHIVO"
        }
    }

    Write-Host "  Esperando 60 segundos..." -ForegroundColor DarkGray
    Start-Sleep -Seconds 60
}
