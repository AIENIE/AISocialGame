package com.aisocialgame.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Component
@ConfigurationProperties(prefix="prompts")
public class PromptProperties {
    private AiName aiName=new AiName();
    public AiName getAiName() { return aiName; }
    public void setAiName(AiName aiName) { this.aiName=aiName; }

    public static class AiName {
        private static final List<String> DEFAULT_ADJECTIVES = List.of(
                "机敏的", "神秘的", "俏皮的", "沉稳的", "勇敢的",
                "灵光一闪的", "幽默的", "谨慎的", "率真的", "冷静的"
        );

        private static final List<String> DEFAULT_SUFFIXES = List.of(
                "玩家", "侦探", "旅人", "观察家", "解谜人",
                "吟游者", "思考者", "夜行者", "梦者", "挑战者"
        );

        private List<String> adjectives = DEFAULT_ADJECTIVES;
        private List<String> suffixes = DEFAULT_SUFFIXES;

        public List<String> getAdjectives() {
            return CollectionUtils.isEmpty(adjectives) ? DEFAULT_ADJECTIVES : adjectives;
        }

        public void setAdjectives(List<String> adjectives) {
            this.adjectives = adjectives;
        }

        public List<String> getSuffixes() {
            return CollectionUtils.isEmpty(suffixes) ? DEFAULT_SUFFIXES : suffixes;
        }

        public void setSuffixes(List<String> suffixes) {
            this.suffixes = suffixes;
        }
    }

}
