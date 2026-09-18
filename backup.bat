@echo off
echo %DATE% %TIME% - EJECUTADO >> C:\backups_tienda\log_backup.txt
SET PGPASSWORD=root
SET BIN=C:\Program Files\PostgreSQL\18\bin
SET ARCHIVO=C:\backups_tienda\tienda_backup.backup

REM Verificar que la BD exista antes de hacer backup
"%BIN%\psql.exe" -U postgres -h localhost -p 5432 -lqt | findstr /i "tienda" > nul
IF %ERRORLEVEL% NEQ 0 (
    echo %DATE% %TIME% - SKIP: BD tienda no existe >> C:\backups_tienda\log_backup.txt
    EXIT /B 0
)

REM Ejecutar pg_dump (sobreescribe el mismo archivo)
"%BIN%\pg_dump.exe" -U postgres -h localhost -p 5432 -d tienda -F c -f "%ARCHIVO%"

IF %ERRORLEVEL% EQU 0 (
    echo %DATE% %TIME% - BACKUP OK >> C:\backups_tienda\log_backup.txt
) ELSE (
    echo %DATE% %TIME% - ERROR en backup >> C:\backups_tienda\log_backup.txt
)
