package com.example.demo.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.demo.service.CloudBackupService;

/**
 * Controlador para el panel de backup en la nube.
 * Expone:
 *   GET /backup         → panel con estado actual
 *   GET /backup/ejecutar → fuerza un backup ahora
 *   GET /backup/restaurar → fuerza un restore ahora
 */
@Controller
@RequestMapping("/backup")
public class BackupController {

    private final CloudBackupService backupService;

    public BackupController(CloudBackupService backupService) {
        this.backupService = backupService;
    }

    /** Panel principal: muestra estado del último backup/restore */
    @GetMapping
    public String panel(Model model) {
        model.addAttribute("ultimoBackup",   backupService.getUltimoBackup());
        model.addAttribute("ultimoRestore",  backupService.getUltimoRestore());
        model.addAttribute("estadoConexion", backupService.getEstadoConexion());
        model.addAttribute("totalRegistros", backupService.getTotalRegistros());
        model.addAttribute("folderId",       backupService.getFolderId());
        return "backup";
    }

    /** Fuerza un backup inmediato y redirige al panel */
    @GetMapping("/ejecutar")
    public String ejecutarBackup(RedirectAttributes attrs) {
        String resultado = backupService.hacerBackup();
        attrs.addFlashAttribute("mensaje", resultado);
        return "redirect:/backup";
    }

    /** Fuerza un restore inmediato desde Drive y redirige al panel */
    @GetMapping("/restaurar")
    public String ejecutarRestore(RedirectAttributes attrs) {
        String resultado = backupService.restaurarDesdeNube();
        attrs.addFlashAttribute("mensaje", resultado);
        return "redirect:/backup";
    }
}
