package com.example.demo.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.demo.model.Producto;
import com.example.demo.repository.ProductoRepository;

@Service
public class ProductoService {

	private ProductoRepository repository;

    public ProductoService(ProductoRepository repository) {
        this.repository = repository;
    }

    public List<Producto> listar(){
        return repository.findAll();
    }

    public void guardar(Producto p){
        repository.save(p);
    }

    public void eliminar(Integer id){
        repository.deleteById(id);
    }

    public Producto buscarPorId(Integer id){
        return repository.findById(id).orElse(null);
    }
}
