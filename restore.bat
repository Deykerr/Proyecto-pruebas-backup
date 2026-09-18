@echo off
SETLOCAL ENABLEDELAYEDEXPANSION
SET PGPASSWORD=root
SET BIN=C:\Program Files\PostgreSQL\18\bin
SET ARCHIVO=C:\backups_tienda\tienda_backup.backup
SET LOG=C:\backups_tienda\log_restore.txt

REM PASO 1: Si la BD existe, no hacer nada
"%BIN%\psql.exe" -U postgres -h localhost -p 5432 -lqt | findstr /i "tienda" > nul
IF %ERRORLEVEL% EQU 0 (
    echo %DATE% %TIME% - BD tienda OK, sin accion >> %LOG%
    EXIT /B 0
)

echo %DATE% %TIME% - BD tienda NO existe. Restaurando... >> %LOG%

REM PASO 2: Verificar que existe el archivo de backup
IF NOT EXIST "%ARCHIVO%" (
    echo %DATE% %TIME% - ERROR: No se encontro %ARCHIVO% >> %LOG%
    EXIT /B 1
)

REM PASO 3: Crear la BD vacia
"%BIN%\createdb.exe" -U postgres -h localhost -p 5432 tienda

REM PASO 4: Restaurar los datos
"%BIN%\pg_restore.exe" -U postgres -h localhost -p 5432 -d tienda --no-owner --clean --if-exists "%ARCHIVO%"

REM Verificar resultado
"%BIN%\psql.exe" -U postgres -h localhost -p 5432 -d tienda -c "SELECT 1" > nul 2>&1
IF %ERRORLEVEL% EQU 0 (
    echo %DATE% %TIME% - RESTORE OK >> %LOG%
) ELSE (
    echo %DATE% %TIME% - ERROR CRITICO en restore >> %LOG%
)
