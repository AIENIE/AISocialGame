package com.aisocialgame.service;

import com.aisocialgame.config.PromptProperties;
import com.aisocialgame.model.Persona;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Random;

@Service
public class AiNameService {
    private final Random random=new Random();
    private final PromptProperties prompts;
    public AiNameService(PromptProperties prompts) { this.prompts=prompts; }
    public String localName(Persona persona) {
        List<String> adjectives=prompts.getAiName().getAdjectives(), suffixes=prompts.getAiName().getSuffixes();
        return adjectives.get(random.nextInt(adjectives.size()))+persona.getName()+suffixes.get(random.nextInt(suffixes.size()));
    }
}
