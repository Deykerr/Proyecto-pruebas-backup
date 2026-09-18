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

    // ─────────────────────────────────────────────────────────
    // BACKUP AUTOMÁTICO (cron configurado en application.properties)
    // ─────────────────────────────────────────────────────────

    @Scheduled(cron = "${backup.cron}")
    public void backupAutomatico() {
        log.info("[Backup automático] Iniciando...");
        hacerBackup();
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
