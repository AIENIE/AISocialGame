package com.aisocialgame.repository;

import com.aisocialgame.model.TurtleSoupAnswerRule;
import com.aisocialgame.model.TurtleSoupStory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TurtleSoupStoryRepository {
    private final List<TurtleSoupStory> stories = List.of(
            new TurtleSoupStory(
                    "classic_turtle_soup",
                    "海龟汤",
                    "一个男人走进餐厅，点了一碗海龟汤。他喝了一口后脸色大变，随后离开餐厅并结束了自己的生命。这是为什么？",
                    "男人曾经流落荒岛，同伴告诉他吃的是海龟汤才让他活下来。多年后他在餐厅尝到真正的海龟汤，发现当年吃的并不是海龟，而是遇难同伴的肉，因此崩溃。",
                    "easy",
                    List.of("经典", "反转", "推理"),
                    List.of("男人曾经流落荒岛", "当年的汤不是真正的海龟汤", "他意识到自己吃过遇难同伴", "餐厅的味道触发了真相"),
                    List.of("餐厅没有投毒", "汤本身没有问题", "服务员不是凶手"),
                    List.of(
                            new TurtleSoupAnswerRule(List.of("餐厅", "饭店", "店里"), "YES", "事件发生在餐厅，但餐厅不是关键凶手。"),
                            new TurtleSoupAnswerRule(List.of("海龟", "乌龟", "龟汤"), "CLOSE", "海龟汤的味道是触发真相的关键。"),
                            new TurtleSoupAnswerRule(List.of("荒岛", "岛", "流落", "漂流"), "YES", "他曾经流落荒岛。"),
                            new TurtleSoupAnswerRule(List.of("同伴", "朋友", "人肉", "遇难"), "YES", "当年的食物与遇难同伴有关。"),
                            new TurtleSoupAnswerRule(List.of("毒", "下药", "过敏"), "NO", "汤本身没有毒，也不是过敏。"),
                            new TurtleSoupAnswerRule(List.of("服务员", "老板", "厨师"), "NO", "餐厅工作人员不是直接原因。"),
                            new TurtleSoupAnswerRule(List.of("自杀", "结束生命", "崩溃"), "YES", "他是在知道真相后崩溃。")
                    )
            ),
            new TurtleSoupStory(
                    "red_button",
                    "红色按钮",
                    "一个人每天都会按下红色按钮。某天他没有按，很多人因此活了下来。发生了什么？",
                    "他是战争中的导弹发射操作员。每天按按钮是例行测试或待命流程；那天他拒绝执行真实发射命令，避免了灾难。",
                    "medium",
                    List.of("选择", "职责", "反战"),
                    List.of("按钮与武器系统有关", "没有按按钮是主动选择", "很多人活下来是因为灾难被避免"),
                    List.of("按钮不是电梯按钮", "不是医院急救设备"),
                    List.of(
                            new TurtleSoupAnswerRule(List.of("武器", "导弹", "战争", "发射"), "YES", "按钮与武器系统有关。"),
                            new TurtleSoupAnswerRule(List.of("电梯", "门铃", "开关"), "NO", "这不是日常民用按钮。"),
                            new TurtleSoupAnswerRule(List.of("命令", "上级", "执行"), "YES", "他面对的是一次真实命令。"),
                            new TurtleSoupAnswerRule(List.of("救人", "活下来", "避免"), "YES", "没有按按钮避免了灾难。")
                    )
            )
    );

    public TurtleSoupStory select(String storyPack, String difficulty) {
        return stories.stream()
                .filter(story -> !hasText(difficulty) || difficulty.equalsIgnoreCase(story.getDifficulty()))
                .findFirst()
                .orElse(stories.get(0));
    }

    public Optional<TurtleSoupStory> findById(String id) {
        return stories.stream().filter(story -> story.getId().equals(id)).findFirst();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
