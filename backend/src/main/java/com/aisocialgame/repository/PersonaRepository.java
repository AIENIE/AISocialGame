package com.aisocialgame.repository;

import com.aisocialgame.model.Persona;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PersonaRepository {
    private final List<Persona> personas = com.aisocialgame.model.PersonaPresets.all();

    public List<Persona> findAll() {
        return personas;
    }

    public Persona findById(String id) {
        return personas.stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
    }
}
