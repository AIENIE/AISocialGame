package com.aisocialgame.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Central place to manage AI-related prompt templates and word lists.
 * Values are loaded from {@code prompt.yml} so that business code does not hardcode prompts.
 */
@Component
@ConfigurationProperties(prefix = "prompts")
public class PromptProperties {

    private AiName aiName = new AiName();
    private AiTalk aiTalk = new AiTalk();
    private AiDecision aiDecision = new AiDecision();

    public AiName getAiName() {
        return aiName;
    }

    public void setAiName(AiName aiName) {
        this.aiName = aiName;
    }

    public AiTalk getAiTalk() {
        return aiTalk;
    }

    public void setAiTalk(AiTalk aiTalk) {
        this.aiTalk = aiTalk;
    }

    public AiDecision getAiDecision() {
        return aiDecision;
    }

    public void setAiDecision(AiDecision aiDecision) {
        this.aiDecision = aiDecision;
    }

    public static class AiName {
        private static final String DEFAULT_REMOTE_PROMPT = "你是社交推理游戏的取名助手，根据人物设定和风格生成 2-6 个字的中文昵称，不要带标点或空格，仅返回昵称本身。";
        private static final List<String> DEFAULT_ADJECTIVES = List.of(
                "机敏的", "神秘的", "俏皮的", "沉稳的", "勇敢的",
                "灵光一闪的", "幽默的", "谨慎的", "率真的", "冷静的"
        );

        private static final List<String> DEFAULT_SUFFIXES = List.of(
                "玩家", "侦探", "旅人", "观察家", "解谜人",
                "吟游者", "思考者", "夜行者", "梦者", "挑战者"
        );

        private String remotePrompt = DEFAULT_REMOTE_PROMPT;
        private List<String> adjectives = DEFAULT_ADJECTIVES;
        private List<String> suffixes = DEFAULT_SUFFIXES;

        public String getRemotePrompt() {
            return StringUtils.hasText(remotePrompt) ? remotePrompt : DEFAULT_REMOTE_PROMPT;
        }

        public void setRemotePrompt(String remotePrompt) {
            this.remotePrompt = remotePrompt;
        }

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

    public static class AiTalk {
        private static final String DEFAULT_DESCRIPTION_TEMPLATE = "%s，我觉得这个词很特别。";
        private static final String DEFAULT_SUSPICION_TEMPLATE = "我觉得%s号有问题";
        private static final String DEFAULT_VOTE_LOG_TEMPLATE = "%s（AI）已完成投票";

        private String descriptionTemplate = DEFAULT_DESCRIPTION_TEMPLATE;
        private String suspicionTemplate = DEFAULT_SUSPICION_TEMPLATE;
        private String voteLogTemplate = DEFAULT_VOTE_LOG_TEMPLATE;

        public String getDescriptionTemplate() {
            return StringUtils.hasText(descriptionTemplate) ? descriptionTemplate : DEFAULT_DESCRIPTION_TEMPLATE;
        }

        public void setDescriptionTemplate(String descriptionTemplate) {
            this.descriptionTemplate = descriptionTemplate;
        }

        public String getSuspicionTemplate() {
            return StringUtils.hasText(suspicionTemplate) ? suspicionTemplate : DEFAULT_SUSPICION_TEMPLATE;
        }

        public void setSuspicionTemplate(String suspicionTemplate) {
            this.suspicionTemplate = suspicionTemplate;
        }

        public String getVoteLogTemplate() {
            return StringUtils.hasText(voteLogTemplate) ? voteLogTemplate : DEFAULT_VOTE_LOG_TEMPLATE;
        }

        public void setVoteLogTemplate(String voteLogTemplate) {
            this.voteLogTemplate = voteLogTemplate;
        }
    }

    public static class AiDecision {
        private static final String DEFAULT_SYSTEM_PROMPT = """
                你是社交推理游戏中的 AI 玩家决策模块。你必须只依据给定上下文行动，不要泄露不可见信息。
                输出必须是紧凑 JSON，不要使用 Markdown，不要输出分析过程。
                可用字段：content、targetSeat、action、useHeal、reason。
                """;
        private static final String DEFAULT_UNDERCOVER_SPEECH = """
                你正在玩谁是卧底。根据你的词语、身份、人设和前面发言，输出一句 15-30 字描述。
                不能直接说出词语；卧底要尽量贴近平民的表达；平民要有辨识度但不要太直白。
                JSON 格式：{"content":"...","reason":"..."}
                """;
        private static final String DEFAULT_UNDERCOVER_VOTE = """
                你正在玩谁是卧底投票阶段。根据本轮描述和历史投票，选择最可疑的存活玩家。
                JSON 格式：{"targetSeat":数字,"reason":"一句简短理由"}
                """;
        private static final String DEFAULT_WEREWOLF_SPEECH = """
                你正在玩狼人杀白天讨论。根据你的身份、昨夜事件、今天发言和人设，输出 30-80 字发言。
                狼人应伪装成好人；好人应基于可见信息推理；不要暴露不可见信息。
                JSON 格式：{"content":"...","reason":"..."}
                """;
        private static final String DEFAULT_WEREWOLF_VOTE = """
                你正在玩狼人杀白天投票。根据讨论、投票历史、身份和人设，选择一个存活玩家放逐。
                JSON 格式：{"targetSeat":数字,"reason":"一句简短理由"}
                """;
        private static final String DEFAULT_WEREWOLF_NIGHT = """
                你正在玩狼人杀夜晚行动。根据你的角色选择合法行动和目标。
                狼人输出 WOLF_KILL；预言家输出 SEER_CHECK；女巫可输出 WITCH_SAVE 或 WITCH_POISON。
                JSON 格式：{"action":"...","targetSeat":数字,"useHeal":true或false,"reason":"一句简短理由"}
                """;

        private String systemPrompt = DEFAULT_SYSTEM_PROMPT;
        private String undercoverSpeech = DEFAULT_UNDERCOVER_SPEECH;
        private String undercoverVote = DEFAULT_UNDERCOVER_VOTE;
        private String werewolfSpeech = DEFAULT_WEREWOLF_SPEECH;
        private String werewolfVote = DEFAULT_WEREWOLF_VOTE;
        private String werewolfNight = DEFAULT_WEREWOLF_NIGHT;

        public String getSystemPrompt() {
            return StringUtils.hasText(systemPrompt) ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }

        public String getUndercoverSpeech() {
            return StringUtils.hasText(undercoverSpeech) ? undercoverSpeech : DEFAULT_UNDERCOVER_SPEECH;
        }

        public void setUndercoverSpeech(String undercoverSpeech) {
            this.undercoverSpeech = undercoverSpeech;
        }

        public String getUndercoverVote() {
            return StringUtils.hasText(undercoverVote) ? undercoverVote : DEFAULT_UNDERCOVER_VOTE;
        }

        public void setUndercoverVote(String undercoverVote) {
            this.undercoverVote = undercoverVote;
        }

        public String getWerewolfSpeech() {
            return StringUtils.hasText(werewolfSpeech) ? werewolfSpeech : DEFAULT_WEREWOLF_SPEECH;
        }

        public void setWerewolfSpeech(String werewolfSpeech) {
            this.werewolfSpeech = werewolfSpeech;
        }

        public String getWerewolfVote() {
            return StringUtils.hasText(werewolfVote) ? werewolfVote : DEFAULT_WEREWOLF_VOTE;
        }

        public void setWerewolfVote(String werewolfVote) {
            this.werewolfVote = werewolfVote;
        }

        public String getWerewolfNight() {
            return StringUtils.hasText(werewolfNight) ? werewolfNight : DEFAULT_WEREWOLF_NIGHT;
        }

        public void setWerewolfNight(String werewolfNight) {
            this.werewolfNight = werewolfNight;
        }
    }
}
