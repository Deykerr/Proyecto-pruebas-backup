# Guía: pgAgent + Backup en la Nube para GestionBD/Triggers

---

## ¿Qué es pgAgent?

pgAgent es el **scheduler oficial de pgAdmin**. Corre como un **servicio de Windows** en segundo plano y puede ejecutar:
- Scripts SQL (directamente en PostgreSQL)
- Scripts del sistema operativo (`.bat`, `.ps1`, `.exe`)

Con él vas a reemplazar el loop del `auto_backup_restore.ps1` por **jobs configurados en pgAdmin**.

---

## PARTE 1 — Instalar pgAgent

### Paso 1.1 — Instalar pgAgent desde Stack Builder

1. Abre **Stack Builder** (viene con la instalación de PostgreSQL)
   - Menú Inicio → buscar "Stack Builder"
   - Selecciona tu servidor PostgreSQL (ej: `PostgreSQL 18 on port 5432`)
2. En la lista de componentes, expande:
   - **Add-ons, tools and utilities** → selecciona **pgAgent**
3. Haz clic en Next → descarga e instala

> **Alternativa**: Descargar el instalador directamente desde:
> https://www.pgadmin.org/download/pgadmin-4-windows/

### Paso 1.2 — Verificar que pgAgent está disponible

Abre pgAdmin → conecta a tu servidor → en el panel izquierdo (árbol) deberías ver:

```
PostgreSQL 18
  └─ Databases
  └─ ...
  └─ pgAgent Jobs   ← si aparece esto, pgAgent está listo
```

Si **no aparece "pgAgent Jobs"**, necesitas crear el schema de pgAgent:

### Paso 1.3 — Crear el schema de pgAgent en la BD `postgres`

En pgAdmin, abre **Query Tool** sobre la base de datos `postgres` y ejecuta:

```sql
-- Esto crea las tablas internas de pgAgent
-- Solo si pgAgent NO aparece en el árbol
CREATE EXTENSION IF NOT EXISTS pgagent;
```

Si da error de extensión no encontrada, salta al paso de instalación manual.

### Paso 1.4 — Instalar y arrancar el servicio de Windows

Abre **PowerShell como Administrador** y ejecuta:

```powershell
# Verificar si el servicio pgAgent ya existe
Get-Service | Where-Object { $_.Name -like "*pgAgent*" -or $_.Name -like "*pgagent*" }
```

Si no aparece, registrarlo manualmente:

```powershell
# Ruta típica de pgAgent (ajusta según tu versión de PostgreSQL)
$pgAgentExe = "C:\Program Files\pgAgent\bin\pgagent.exe"

# Instalar como servicio de Windows
& $pgAgentExe INSTALL pgAgent -u postgres -p root -d "host=localhost port=5432 dbname=postgres user=postgres password=root"

# Iniciar el servicio
Start-Service pgAgent
```

Verificar que corre:
```powershell
Get-Service pgAgent
# Debe decir: Status = Running
```

---

## PARTE 2 — Configurar Jobs en pgAdmin

### Job 1: Backup automático (cada N minutos)

En pgAdmin:
1. Click derecho sobre **pgAgent Jobs** → **Create** → **pgAgent Job...**
2. En la pestaña **General**:
   - **Name**: `Backup Tienda`
   - **Enabled**: ✅ Yes

#### Pestaña Steps (pasos del job)

Click en **+** para agregar un step:
- **Name**: `Ejecutar pg_dump`
- **Kind**: `Batch` (script de sistema operativo)
- **Connection type**: `Local`
- **Code** (el script .bat a ejecutar):

```bat
@echo off
SET PGPASSWORD=root
SET BIN=C:\Program Files\PostgreSQL\18\bin
SET ARCHIVO=C:\backups_tienda\tienda_backup.backup

REM Solo hacer backup si la BD existe
"%BIN%\psql.exe" -U postgres -h localhost -p 5432 -lqt | findstr /i "tienda" > nul
IF %ERRORLEVEL% NEQ 0 EXIT /B 0

"%BIN%\pg_dump.exe" -U postgres -h localhost -p 5432 -d tienda -F c -f "%ARCHIVO%"
echo %DATE% %TIME% - BACKUP LOCAL OK >> C:\backups_tienda\log_backup.txt
```

#### Pestaña Schedules (frecuencia)

Click en **+** para agregar un horario:
- **Name**: `Cada 2 minutos`
- **Enabled**: ✅
- **Start**: fecha/hora actual
- En la pestaña **Repeat**:
  - **Minutes**: selecciona `00`, `02`, `04`, ... (cada 2 min)
  - O usa la expresión: cada minuto elegido

> [!TIP]
> Para pruebas usa **cada 1 o 2 minutos**. Para producción, cada 30 min o cada hora.

---

### Job 2: Restauración automática (detectar BD caída)

1. Click derecho sobre **pgAgent Jobs** → **Create** → **pgAgent Job...**
2. **Name**: `Restore Tienda si BD caida`

#### Step:
- **Kind**: `Batch`
- **Code**:

```bat
@echo off
SET PGPASSWORD=root
SET BIN=C:\Program Files\PostgreSQL\18\bin
SET ARCHIVO=C:\backups_tienda\tienda_backup.backup
SET LOG=C:\backups_tienda\log_restore.txt

REM Si la BD existe, no hacer nada
"%BIN%\psql.exe" -U postgres -h localhost -p 5432 -lqt | findstr /i "tienda" > nul
IF %ERRORLEVEL% EQU 0 EXIT /B 0

echo %DATE% %TIME% - BD tienda NO existe. Restaurando... >> %LOG%

IF NOT EXIST "%ARCHIVO%" (
    echo %DATE% %TIME% - ERROR: No existe archivo de backup >> %LOG%
    EXIT /B 1
)

"%BIN%\createdb.exe" -U postgres -h localhost -p 5432 tienda
"%BIN%\pg_restore.exe" -U postgres -h localhost -p 5432 -d tienda --no-owner --clean --if-exists "%ARCHIVO%"
echo %DATE% %TIME% - RESTORE LOCAL OK >> %LOG%
```

#### Schedule:
- Misma configuración: cada 1-2 minutos

---

## PARTE 3 — Agregar la nube (Backblaze B2)

Una vez que los Jobs locales funcionen, se amplían los scripts `.bat` agregando un paso de upload/download.

### Paso 3.1 — Crear cuenta en Backblaze B2

1. Ir a: https://www.backblaze.com/sign-up/cloud-storage
2. Crear cuenta (no requiere tarjeta de crédito)
3. En el dashboard → **B2 Cloud Storage** → **Buckets** → **Create a Bucket**
   - Bucket name: `tienda-backups-bd` (debe ser único globalmente)
   - Files: **Private**
4. Ir a **App Keys** → **Add a New Application Key**
   - Allow access to bucket: `tienda-backups-bd`
   - Copiar y guardar el `keyID` y `applicationKey` (solo se muestran una vez)

### Paso 3.2 — Instalar CLI de Backblaze B2

En PowerShell (requiere Python instalado):
```powershell
pip install b2
```

Autenticar:
```powershell
b2 authorize-account <keyID> <applicationKey>
```

Verificar:
```powershell
b2 ls b2://tienda-backups-bd
# Debe listar el contenido (vacío al principio)
```

### Paso 3.3 — Actualizar el Job de Backup en pgAdmin

Editar el step del **Job "Backup Tienda"** → reemplazar el Code con:

```bat
@echo off
SET PGPASSWORD=root
SET BIN=C:\Program Files\PostgreSQL\18\bin
SET ARCHIVO=C:\backups_tienda\tienda_backup.backup
SET BUCKET=tienda-backups-bd
SET LOG=C:\backups_tienda\log_backup.txt

REM Solo hacer backup si la BD existe
"%BIN%\psql.exe" -U postgres -h localhost -p 5432 -lqt | findstr /i "tienda" > nul
IF %ERRORLEVEL% NEQ 0 (
    echo %DATE% %TIME% - SKIP: BD no existe >> %LOG%
    EXIT /B 0
)

REM Backup local
"%BIN%\pg_dump.exe" -U postgres -h localhost -p 5432 -d tienda -F c -f "%ARCHIVO%"
IF %ERRORLEVEL% NEQ 0 (
    echo %DATE% %TIME% - ERROR en pg_dump >> %LOG%
    EXIT /B 1
)
echo %DATE% %TIME% - BACKUP LOCAL OK >> %LOG%

REM Subir a Backblaze B2
b2 file upload-file %BUCKET% "%ARCHIVO%" tienda_backup.backup
IF %ERRORLEVEL% EQU 0 (
    echo %DATE% %TIME% - SUBIDA A NUBE OK >> %LOG%
) ELSE (
    echo %DATE% %TIME% - ERROR al subir a nube >> %LOG%
)
```

### Paso 3.4 — Actualizar el Job de Restore en pgAdmin

Editar el step del **Job "Restore Tienda si BD caida"** → reemplazar con:

```bat
@echo off
SET PGPASSWORD=root
SET BIN=C:\Program Files\PostgreSQL\18\bin
SET ARCHIVO=C:\backups_tienda\tienda_backup.backup
SET BUCKET=tienda-backups-bd
SET LOG=C:\backups_tienda\log_restore.txt

REM Si la BD existe, no hacer nada
"%BIN%\psql.exe" -U postgres -h localhost -p 5432 -lqt | findstr /i "tienda" > nul
IF %ERRORLEVEL% EQU 0 EXIT /B 0

echo %DATE% %TIME% - BD tienda NO existe. Iniciando restore... >> %LOG%

REM Intentar descargar desde la nube
b2 file download-file-by-name %BUCKET% tienda_backup.backup "%ARCHIVO%"
IF %ERRORLEVEL% EQU 0 (
    echo %DATE% %TIME% - DESCARGA DESDE NUBE OK >> %LOG%
) ELSE (
    echo %DATE% %TIME% - No se pudo bajar de nube, usando local si existe >> %LOG%
)

REM Verificar que el archivo de backup existe (local o descargado)
IF NOT EXIST "%ARCHIVO%" (
    echo %DATE% %TIME% - ERROR: No hay backup disponible >> %LOG%
    EXIT /B 1
)

REM Crear la BD y restaurar
"%BIN%\createdb.exe" -U postgres -h localhost -p 5432 tienda
"%BIN%\pg_restore.exe" -U postgres -h localhost -p 5432 -d tienda --no-owner --clean --if-exists "%ARCHIVO%"

IF %ERRORLEVEL% EQU 0 (
    echo %DATE% %TIME% - RESTORE OK >> %LOG%
) ELSE (
    echo %DATE% %TIME% - ERROR en restore >> %LOG%
)
```

---

## Resumen del flujo final

```
pgAgent (servicio Windows, corriendo siempre)
    │
    ├─ Job "Backup Tienda" (cada 2 min)
    │       ↓
    │   ¿BD "tienda" existe?
    │   SÍ → pg_dump → .backup local → b2 upload → ☁ Backblaze B2
    │   NO → skip
    │
    └─ Job "Restore Tienda" (cada 2 min)
            ↓
        ¿BD "tienda" existe?
        SÍ → skip
        NO → b2 download ← ☁ Backblaze B2
             → createdb + pg_restore → BD restaurada ✔
```

---

## Verificar que todo funciona

Puedes ver el historial de ejecución en pgAdmin:
- Click sobre el Job → pestaña **Statistics** o **History**
- Verás fecha/hora de ejecución y si fue exitoso ✅ o fallido ❌

También revisar los logs en:
```
C:\backups_tienda\log_backup.txt
C:\backups_tienda\log_restore.txt
```
