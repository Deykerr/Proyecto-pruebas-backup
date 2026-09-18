# Script de configuracion automatica de backup y restauracion
# Ejecutar como ADMINISTRADOR en PowerShell

Write-Host "=== Configurando Backup Automatico PostgreSQL ===" -ForegroundColor Cyan

# 1. Crear carpeta de backups
$backupDir = "C:\backups_tienda"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
Write-Host "✅ Carpeta creada: $backupDir" -ForegroundColor Green

# 2. Copiar scripts a la carpeta de backups
$projectPath = "c:\Users\LOQ\Documents\8C\GestionBD\Triggers"
Copy-Item "$projectPath\backup.bat" "$backupDir\backup.bat" -Force
Copy-Item "$projectPath\restore.bat" "$backupDir\restore.bat" -Force
Write-Host "✅ Scripts copiados a $backupDir" -ForegroundColor Green

# 3. Probar el backup manualmente primero
Write-Host "`n⏳ Ejecutando backup de prueba..." -ForegroundColor Yellow
& "$backupDir\backup.bat"
Write-Host "✅ Backup de prueba completado" -ForegroundColor Green

# 4. Crear tarea programada de BACKUP cada minuto
Write-Host "`n⏳ Registrando tarea de BACKUP..." -ForegroundColor Yellow

$actionBackup = New-ScheduledTaskAction `
    -Execute "cmd.exe" `
    -Argument "/c `"$backupDir\backup.bat`""

$triggerBackup = New-ScheduledTaskTrigger `
    -RepetitionInterval (New-TimeSpan -Minutes 1) `
    -Once `
    -At (Get-Date)

$settings = New-ScheduledTaskSettingsSet `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew

Register-ScheduledTask `
    -TaskName "PostgreSQL_Backup_Tienda" `
    -Action $actionBackup `
    -Trigger $triggerBackup `
    -Settings $settings `
    -RunLevel Highest `
    -Force | Out-Null

Write-Host "✅ Tarea de BACKUP registrada (cada 1 minuto)" -ForegroundColor Green

# 5. Crear tarea programada de RESTORE cada minuto
Write-Host "`n⏳ Registrando tarea de RESTAURACION..." -ForegroundColor Yellow

$actionRestore = New-ScheduledTaskAction `
    -Execute "cmd.exe" `
    -Argument "/c `"$backupDir\restore.bat`""

$triggerRestore = New-ScheduledTaskTrigger `
    -RepetitionInterval (New-TimeSpan -Minutes 1) `
    -Once `
    -At (Get-Date).AddSeconds(30)  # 30 seg despues del backup

Register-ScheduledTask `
    -TaskName "PostgreSQL_Restore_Tienda" `
    -Action $actionRestore `
    -Trigger $triggerRestore `
    -Settings $settings `
    -RunLevel Highest `
    -Force | Out-Null

Write-Host "✅ Tarea de RESTAURACION registrada (cada 1 minuto)" -ForegroundColor Green

# 6. Verificar que las tareas existen
Write-Host "`n=== Tareas Programadas Registradas ===" -ForegroundColor Cyan
Get-ScheduledTask | Where-Object { $_.TaskName -like "*Tienda*" } | 
    Select-Object TaskName, State | Format-Table -AutoSize

Write-Host "`n🎉 Configuracion completada!" -ForegroundColor Green
Write-Host "📁 Los backups se guardan en: $backupDir" -ForegroundColor White
Write-Host "📋 Revisa los logs en: $backupDir\log_backup.txt" -ForegroundColor White
Write-Host "`nPara ver el Programador de Tareas: taskschd.msc" -ForegroundColor Gray
