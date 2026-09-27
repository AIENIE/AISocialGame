package com.aisocialgame.service;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.config.PromptProperties;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatMessageDto;
import com.aisocialgame.model.Persona;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Random;

@Service
public class AiNameService {

    private final Random random = new Random();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.aisocialgame.service.safety.AiSafetyService safety;
    private final PromptProperties promptProperties;
    private final AiGrpcClient aiGrpcClient;
    private final AppProperties appProperties;

    public AiNameService(PromptProperties promptProperties,
                         AiGrpcClient aiGrpcClient,
                         AppProperties appProperties) {
        this.promptProperties = promptProperties;
        this.aiGrpcClient = aiGrpcClient;
        this.appProperties = appProperties;
    }

    public String generateName(Persona persona) {
        String aiName = tryRemoteGeneration(persona);
        if (StringUtils.hasText(aiName)) {
            return aiName;
        }
        return localName(persona);
    }

    private String tryRemoteGeneration(Persona persona) {
        try {
            String prompt = promptProperties.getAiName().getRemotePrompt();
            String content = "角色设定：" + persona.getName() + "；风格：" + persona.getTrait() + "。请返回一个 2-6 字中文昵称，仅输出昵称。";
            if (StringUtils.hasText(prompt)) {
                content = prompt + "\n" + content;
            }
            var response = aiGrpcClient.chatCompletions(
                    appProperties.getProjectKey(),
                    appProperties.getAi().getSystemUserId(),
                    "",
                    appProperties.getAi().getDefaultModel(),
                    List.of(new AiChatMessageDto("user", content))
            );
            if(safety!=null)safety.requireCallAllowed(com.aisocialgame.service.safety.AiCallScope.context(appProperties.getAi().getSystemUserId(),appProperties.getAi().getDefaultModel()));
            String normalized = sanitize(response.content());
            if(safety!=null)normalized=safety.requireAllowedInput(normalized,com.aisocialgame.service.safety.AiCallScope.context(appProperties.getAi().getSystemUserId(),appProperties.getAi().getDefaultModel()));
            if (StringUtils.hasText(normalized)) {
                return normalized;
            }
        } catch (Exception ignored) {
            // AI 服务短暂不可用时回退本地策略，避免影响开房流程。
        }
        return null;
    }

    public String localName(Persona persona) {
        List<String> adjectives = promptProperties.getAiName().getAdjectives();
        List<String> suffixes = promptProperties.getAiName().getSuffixes();
        if (adjectives.isEmpty() || suffixes.isEmpty()) {
            return persona.getName();
        }
        String prefix = adjectives.get(random.nextInt(adjectives.size()));
        String suffix = suffixes.get(random.nextInt(suffixes.size()));
        return prefix + persona.getName() + suffix;
    }

    private String sanitize(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String line = content.strip().replace(" ", "");
        int newline = line.indexOf('\n');
        if (newline > 0) {
            line = line.substring(0, newline).trim();
        }
        line = line.replace("“", "").replace("”", "").replace("\"", "");
        if (line.length() > 12) {
            line = line.substring(0, 12);
        }
        return line;
    }
}
