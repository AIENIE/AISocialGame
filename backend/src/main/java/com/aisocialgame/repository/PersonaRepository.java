package com.aisocialgame.repository;

import com.aisocialgame.model.Persona;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PersonaRepository {
    private final List<Persona> personas = List.of(
            new Persona("ai1", "福尔摩斯", "逻辑严密", "https://api.dicebear.com/7.x/avataaars/svg?seed=Sherlock", "冷静、短句、会引用证据", "优先寻找矛盾和弱发言", 3, "习惯把玩家发言按可信度排序"),
            new Persona("ai2", "小丑", "混乱邪恶", "https://api.dicebear.com/7.x/avataaars/svg?seed=Joker", "跳跃、挑衅、偶尔反问", "制造压力并测试他人反应", 2, "喜欢用轻佻语气掩盖真实意图"),
            new Persona("ai3", "华生", "辅助型", "https://api.dicebear.com/7.x/avataaars/svg?seed=Watson", "温和、解释型、照顾新手", "跟随强逻辑并补充细节", 1, "倾向先合作再质疑"),
            new Persona("ai4", "露娜", "神秘莫测", "https://api.dicebear.com/7.x/avataaars/svg?seed=Luna", "含蓄、观察型、少量暗示", "保留信息并在关键轮次转向", 2, "会记住别人前后语气变化")
    );

    public List<Persona> findAll() {
        return personas;
    }

    public Persona findById(String id) {
        return personas.stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
    }
}
