package com.example.demo.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.example.demo.model.Producto;
import com.example.demo.service.ProductoService;

@Controller
@RequestMapping("/productos")
public class ProductoController {

	 private ProductoService service;

	    public ProductoController(ProductoService service) {
	        this.service = service;
	    }

	    @GetMapping
	    public String listar(Model model){
	        try {
	            model.addAttribute("productos", service.listar());
	            model.addAttribute("dbOk", true);
	            return "productos";
	        } catch (Exception e) {
	            model.addAttribute("error", "Base de datos no disponible: " + e.getMessage());
	            model.addAttribute("dbOk", false);
	            return "error-bd";
	        }
	    }

	    @GetMapping("/nuevo")
	    public String nuevo(Model model){

	        model.addAttribute("producto", new Producto());

	        return "form";
	    }

	    @PostMapping("/guardar")
	    public String guardar(@ModelAttribute Producto producto){

	        service.guardar(producto);

	        return "redirect:/productos";
	    }

	    @GetMapping("/eliminar/{id}")
	    public String eliminar(@PathVariable Integer id){

	        service.eliminar(id);

	        return "redirect:/productos";
	    }

	    @GetMapping("/editar/{id}")
	    public String editar(@PathVariable Integer id, Model model){

	        model.addAttribute("producto", service.buscarPorId(id));

	        return "form";
	    }
}
