package com.aisocialgame.engine.v2;
import com.aisocialgame.model.Game;
import com.aisocialgame.engine.ValidationResult;
import java.util.Map;
public final class GameConfiguration {
    private GameConfiguration() {}
    public static Game withDifficulty(Game game) {
        var fields=new java.util.ArrayList<>(game.getConfigSchema());
        if(fields.stream().noneMatch(f -> "aiDifficulty".equals(f.getId())))fields.add(new com.aisocialgame.model.GameConfigOption("aiDifficulty","AI 难度","select",2,
                java.util.List.of(new com.aisocialgame.model.GameConfigOption.Option("简单",1),new com.aisocialgame.model.GameConfigOption.Option("娱乐",2),new com.aisocialgame.model.GameConfigOption.Option("进阶",3)),null,null));
        game.setConfigSchema(fields);return game;
    }
    public static ValidationResult validate(Game game, Map<String,Object> config) {
        for (var field:game.getConfigSchema()) {
            Object value=config.get(field.getId()); if(value==null) continue;
            if (field.getOptions()!=null && !field.getOptions().isEmpty() && field.getOptions().stream().noneMatch(o -> String.valueOf(o.value()).equals(String.valueOf(value))))
                return ValidationResult.invalid("配置选项无效："+field.getId());
            if ("boolean".equals(field.getType()) && !(value instanceof Boolean)) return ValidationResult.invalid("配置类型无效："+field.getId());
            if ("number".equals(field.getType())) {
                if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=Math.rint(n.doubleValue())) return ValidationResult.invalid("配置类型无效："+field.getId());
                double number=((Number)value).doubleValue();
                if (field.getMin()!=null&&number<field.getMin() || field.getMax()!=null&&number>field.getMax()) return ValidationResult.invalid("配置范围无效："+field.getId());
            }
        }
        return ValidationResult.ok();
    }
}
