package com.example.demo.service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.demo.model.Producto;
import com.example.demo.repository.ProductoRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

/**
 * Servicio de backup/restore en Google Drive.
 *
 * Flujo de backup:
 *   1. Lee todos los productos de la BD via JPA
 *   2. Serializa a JSON con Jackson
 *   3. Sube (o actualiza) el archivo en Google Drive
 *
 * Flujo de restore:
 *   1. Busca el archivo de backup en Drive
 *   2. Descarga el JSON
 *   3. Deserializa y guarda los productos via JPA
 */
@Service
public class CloudBackupService {

    private static final Logger log = LoggerFactory.getLogger(CloudBackupService.class);
    private static final String APP_NAME = "GestionBD-Tienda";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String TOKENS_DIR = "tokens";

    @Value("${backup.drive.credentials-path}")
    private String credentialsPath;

    @Value("${backup.drive.folder-id}")
    private String folderId;

    @Value("${backup.drive.file-name}")
    private String backupFileName;

    // true = Service Account (Railway), false = OAuth2 Desktop (local)
    @Value("${backup.drive.use-service-account:false}")
    private boolean useServiceAccount;

    // JSON de la Service Account (solo en Railway, via variable de entorno)
    @Value("${backup.drive.service-account-json:}")
    private String serviceAccountJson;

    private final ProductoRepository repository;
    // ObjectMapper instanciado directamente (no requiere bean de Spring)
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Estado visible en la UI
    private String ultimoBackup   = "Sin backup registrado";
    private String ultimoRestore  = "Sin restore registrado";
    private String estadoConexion = "OK";
    private int totalRegistros    = 0;

    public CloudBackupService(ProductoRepository repository) {
        this.repository = repository;
    }

    @jakarta.annotation.PostConstruct
    public void setupTokens() {
        try {
            java.io.File tokensDir = new java.io.File(TOKENS_DIR);
            if (!tokensDir.exists()) {
                tokensDir.mkdirs();
            }
            java.io.File storedCredential = new java.io.File(tokensDir, "StoredCredential");
            // Sobrescribir siempre para asegurar que no haya archivos corruptos
            String b64 = "rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcAUH2sHDFmDRAwACRgAKbG9hZEZhY3RvckkACXRocmVzaG9sZHhwP0AAAAAAAAx3CAAA" +
"ABAAAAABdAAEdXNlcnVyAAJbQqzzF/gGCFTgAgAAeHAAAAQarO0ABXNyADJjb20uZ29vZ2xlLmFwaS5jbGllbnQuYXV0aC5vYXV0" +
"aDIuU3RvcmVkQ3JlZGVudGlhbAAAAAAAAAABAgAETAALYWNjZXNzVG9rZW50ABJMamF2YS9sYW5nL1N0cmluZztMABpleHBpcmF0" +
"aW9uVGltZU1pbGxpc2Vjb25kc3QAEExqYXZhL2xhbmcvTG9uZztMAARsb2NrdAAhTGphdmEvdXRpbC9jb25jdXJyZW50L2xvY2tz" +
"L0xvY2s7TAAMcmVmcmVzaFRva2VucQB+AAF4cHQA/nlhMjkuYTBBWDA3Q210d2lHbVJ3dUJUTEhoOEdPMXJPMlFjN3E0ckZyTkJo" +
"MkNUYzlNcHAtT0VPMnpFYkJqdldHMEF3VlVTOEdqUFAzZHh4ckVKREpNX2FkOW4xa3FlZXIzM3pOS1IxYl9HY1ZqZnY2MmdGb21v" +
"Z1JpTkNtVmxvcEVHT2NDbUZ2MzFObVRQWVFOYl9pZmZTYWZ3Y2cyTGJzb0RmdV9Ha0Jkb1hQUkctTTVSSDB3cjRTU2JEdnEwUU0x" +
"VFJqWkhQX1U5UVZjNmFDZ1lLQWJFU0FSRVNGUUhHWDJNaS1JOHFvekl6cWhKM2lxOFRabDJseVEwMjA3c3IADmphdmEubGFuZy5M" +
"b25nO4vkkMyPI98CAAFKAAV2YWx1ZXhyABBqYXZhLmxhbmcuTnVtYmVyhqyVHQuU4IsCAAB4cAAAAaDVRnPRc3IAKGphdmEudXRp" +
"bC5jb25jdXJyZW50LmxvY2tzLlJlZW50cmFudExvY2tmVagsLMhq6wIAAUwABHN5bmN0AC9MamF2YS91dGlsL2NvbmN1cnJlbnQv" +
"bG9ja3MvUmVlbnRyYW50TG9jayRTeW5jO3hwc3IANGphdmEudXRpbC5jb25jdXJyZW50LmxvY2tzLlJlZW50cmFudExvY2skTm9u" +
"ZmFpclN5bmNliDLnU3u/CwIAAHhyAC1qYXZhLnV0aWwuY29uY3VycmVudC5sb2Nrcy5SZWVudHJhbnRMb2NrJFN5bmO4HqKUqkRa" +
"fAIAAHhyADVqYXZhLnV0aWwuY29uY3VycmVudC5sb2Nrcy5BYnN0cmFjdFF1ZXVlZFN5bmNocm9uaXplcmZVqEN1P1LjAgABSQAF" +
"c3RhdGV4cgA2amF2YS51dGlsLmNvbmN1cnJlbnQubG9ja3MuQWJzdHJhY3RPd25hYmxlU3luY2hyb25pemVyM9+vua1tb6kCAAB4" +
"cAAAAAB0AGcxLy8waHVHdkxpSDIyX3BVQ2dZSUFSQUFHQkVTTndGLUw5SXJlbzRuOGZubWtmNk5NTnk2YXNHY01iSGxCUXpZWGE3" +
"U3BHcGRkQmlNN251SHRYRXl5dGxmZkxXUGVKdHVmaXRDby04eA==";
            byte[] decoded = java.util.Base64.getDecoder().decode(b64);
            java.nio.file.Files.write(storedCredential.toPath(), decoded);
            log.info("[Drive] Archivo StoredCredential inyectado directamente en {}", storedCredential.getAbsolutePath());
        } catch (Exception e) {
            log.error("Error inyectando el token: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // BACKUP AUTOMÁTICO (cron configurado en application.properties)
    // ─────────────────────────────────────────────────────────

    @Scheduled(cron = "${backup.cron}")
    public void backupAutomatico() {
        log.info("[Backup automático] Iniciando...");
        // Si la base de datos está vacía, no queremos hacer backup y sobrescribir la nube con 0 datos!
        if (repository.count() > 0) {
            hacerBackup();
        } else {
            log.warn("[Backup automático] Omitido porque la base de datos está vacía (evitando borrar backup en la nube).");
        }
    }

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/tienda}")
    private String dbUrl;

    @Value("${spring.datasource.username:postgres}")
    private String dbUser;

    @Value("${spring.datasource.password:root}")
    private String dbPassword;

    // ─────────────────────────────────────────────────────────
    // AUTO-RESTORE (DISASTER RECOVERY AUTOMÁTICO)
    // ─────────────────────────────────────────────────────────
    @Scheduled(fixedDelay = 60000) // Se ejecuta cada 1 minuto (60000 ms)
    public void disasterRecoveryAutomatico() {
        try {
            // Si detecta que no hay productos (alguien borró los datos)
            if (repository.count() == 0) {
                log.warn("¡ALERTA! [Disaster Recovery] Se detectó la base de datos vacía. Iniciando auto-restauración desde la nube...");
                String resultado = restaurarDesdeNube();
                log.info("[Disaster Recovery] Resultado: {}", resultado);
            }
        } catch (Exception e) {
            log.error("[Disaster Recovery] Error al intentar verificar la base de datos: {}", e.getMessage());
            
            // Buscar en toda la cadena de excepciones (Root Cause)
            boolean dbNoExiste = false;
            boolean tablaNoExiste = false;
            Throwable causa = e;
            while (causa != null) {
                String msg = causa.getMessage();
                if (msg != null) {
                    if (msg.contains("database") && (msg.contains("does not exist") || msg.contains("no existe"))) {
                        dbNoExiste = true;
                    }
                    if (msg.contains("relation \"productos\" does not exist") || msg.contains("relación «productos» no existe")) {
                        tablaNoExiste = true;
                    }
                }
                causa = causa.getCause();
            }
            
            // Si el error es porque eliminaron la tabla (pero la base de datos sí existe)
            if (tablaNoExiste && !dbNoExiste) {
                log.warn("¡ALERTA! Parece que borraron la tabla 'productos' pero la BD existe. Intentando recrear la tabla...");
                try {
                    java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                    java.sql.Statement stmt = conn.createStatement();
                    stmt.executeUpdate("CREATE TABLE productos (id SERIAL PRIMARY KEY, nombre VARCHAR(255), precio DOUBLE PRECISION)");
                    stmt.close();
                    conn.close();
                    log.info("[Disaster Recovery] ¡Tabla 'productos' recreada exitosamente! Procediendo a auto-restaurar los datos...");
                    restaurarDesdeNube();
                } catch (Exception sqlEx) {
                    log.error("[Disaster Recovery] No se pudo auto-recrear la tabla: {}", sqlEx.getMessage());
                }
            }
            // Si el error es porque eliminaron la base de datos entera (DROP DATABASE)
            else if (dbNoExiste) {
                log.warn("¡ALERTA MÁXIMA! Parece que borraron la base de datos completa. Intentando recrear la infraestructura...");
                try {
                    // Extraer host y puerto de la URL
                    String baseUrl = dbUrl.substring(0, dbUrl.lastIndexOf('/')) + "/postgres";
                    String dbName = dbUrl.substring(dbUrl.lastIndexOf('/') + 1);
                    if (dbName.contains("?")) {
                        dbName = dbName.substring(0, dbName.indexOf('?'));
                    }
                    
                    java.sql.Connection conn = java.sql.DriverManager.getConnection(baseUrl, dbUser, dbPassword);
                    java.sql.Statement stmt = conn.createStatement();
                    stmt.executeUpdate("CREATE DATABASE " + dbName);
                    stmt.close();
                    conn.close();
                    log.info("[Disaster Recovery] ¡Base de datos '" + dbName + "' recreada exitosamente! En el siguiente ciclo se creará la tabla.");
                } catch (Exception sqlEx) {
                    log.error("[Disaster Recovery] No se pudo auto-recrear la BD: {}", sqlEx.getMessage());
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // HACER BACKUP
    // ─────────────────────────────────────────────────────────

    public String hacerBackup() {
        try {
            if (folderId == null || folderId.isBlank()) {
                String msg = "ERROR: backup.drive.folder-id no configurado en application.properties";
                log.error(msg);
                return msg;
            }

            // 1. Leer todos los productos de la BD
            List<Producto> productos = repository.findAll();
            totalRegistros = productos.size();

            // 2. Serializar a JSON
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                                      .writeValueAsString(productos);

            // 3. Subir a Drive (actualizar si existe, crear si no)
            Drive drive = buildDriveService();
            String fileId = buscarArchivoEnDrive(drive, backupFileName);

            byte[] contenido = json.getBytes(StandardCharsets.UTF_8);
            ByteArrayContent mediaContent = new ByteArrayContent("application/json", contenido);

            if (fileId != null) {
                drive.files().update(fileId, new File(), mediaContent).execute();
                log.info("[Backup] Archivo actualizado en Drive. Registros: {}", totalRegistros);
            } else {
                File meta = new File();
                meta.setName(backupFileName);
                meta.setParents(Collections.singletonList(folderId));
                drive.files().create(meta, mediaContent).setFields("id").execute();
                log.info("[Backup] Archivo creado en Drive. Registros: {}", totalRegistros);
            }

            ultimoBackup = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
                + " — " + totalRegistros + " registros";
            estadoConexion = "OK";
            return "Backup exitoso: " + ultimoBackup;

        } catch (Exception e) {
            String msg = "ERROR en backup: " + e.getMessage();
            log.error(msg, e);
            estadoConexion = "ERROR";
            return msg;
        }
    }

    // ─────────────────────────────────────────────────────────
    // RESTAURAR DESDE DRIVE
    // ─────────────────────────────────────────────────────────

    public String restaurarDesdeNube() {
        try {
            if (folderId == null || folderId.isBlank()) {
                String msg = "ERROR: backup.drive.folder-id no configurado en application.properties";
                log.error(msg);
                return msg;
            }

            Drive drive = buildDriveService();
            String fileId = buscarArchivoEnDrive(drive, backupFileName);

            if (fileId == null) {
                String msg = "ERROR: No se encontró archivo de backup en Drive (" + backupFileName + ")";
                log.error(msg);
                return msg;
            }

            // Descargar y deserializar
            InputStream stream = drive.files().get(fileId).executeMediaAsInputStream();
            List<Producto> productos = objectMapper.readValue(
                stream, new TypeReference<List<Producto>>() {});

            // Repoblar la tabla
            repository.deleteAll();
            for (Producto p : productos) {
                p.setId(null); // La BD asigna nuevo ID
                repository.save(p);
            }

            ultimoRestore = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
                + " — " + productos.size() + " registros restaurados";
            log.info("[Restore] {} productos restaurados desde Drive", productos.size());
            return "Restore exitoso: " + ultimoRestore;

        } catch (Exception e) {
            String msg = "ERROR en restore: " + e.getMessage();
            log.error(msg, e);
            return msg;
        }
    }

    // ─────────────────────────────────────────────────────────
    // GETTERS de estado (usados por el controller)
    // ─────────────────────────────────────────────────────────

    public String getUltimoBackup()   { return ultimoBackup; }
    public String getUltimoRestore()  { return ultimoRestore; }
    public String getEstadoConexion() { return estadoConexion; }
    public int    getTotalRegistros() { return totalRegistros; }
    public String getFolderId()       { return folderId; }

    // ─────────────────────────────────────────────────────────
    // INTERNOS — Google Drive (OAuth2 local ó Service Account servidor)
    // ─────────────────────────────────────────────────────────

    /**
     * Construye el cliente Drive.
     * - LOCAL:    OAuth2 Desktop (abre el navegador la primera vez, guarda token)
     * - RAILWAY:  Service Account (sin browser, lee JSON de variable de entorno)
     */
    private Drive buildDriveService() throws Exception {
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        if (useServiceAccount) {
            // ── Modo servidor (Railway) ── Service Account ──
            log.info("[Drive] Usando Service Account (modo servidor)");
            InputStream saStream = new java.io.ByteArrayInputStream(
                serviceAccountJson.getBytes(StandardCharsets.UTF_8));
            GoogleCredentials credentials = GoogleCredentials
                .fromStream(saStream)
                .createScoped(Collections.singletonList(DriveScopes.DRIVE_FILE));
            return new Drive.Builder(httpTransport, JSON_FACTORY,
                    new HttpCredentialsAdapter(credentials))
                .setApplicationName(APP_NAME)
                .build();
        } else {
            // ── Modo local ── OAuth2 Desktop ──
            log.info("[Drive] Usando OAuth2 Desktop (modo local)");
            GoogleClientSecrets clientSecrets;
            try (var in = new FileInputStream(credentialsPath);
                 var reader = new InputStreamReader(in)) {
                clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, reader);
            }

            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets,
                Collections.singletonList(DriveScopes.DRIVE_FILE))
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIR)))
                .setAccessType("offline")
                .build();

            // Puerto -1 = el SO asigna automáticamente un puerto libre
            LocalServerReceiver receiver = new LocalServerReceiver.Builder()
                .setPort(-1)
                .build();

            Credential credential = new AuthorizationCodeInstalledApp(flow, receiver)
                .authorize("user");

            return new Drive.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APP_NAME)
                .build();
        }
    }

    /**
     * Busca un archivo por nombre dentro de la carpeta de Drive configurada.
     * Retorna el fileId si existe, null si no.
     */
    private String buscarArchivoEnDrive(Drive drive, String nombre) throws IOException {
        String query = String.format(
            "name='%s' and '%s' in parents and trashed=false",
            nombre, folderId);

        FileList result = drive.files().list()
            .setQ(query)
            .setFields("files(id, name)")
            .execute();

        List<File> files = result.getFiles();
        return (files != null && !files.isEmpty()) ? files.get(0).getId() : null;
    }
}
