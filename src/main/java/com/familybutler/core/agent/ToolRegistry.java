package com.familybutler.core.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：Spring 把所有 ButlerTool Bean 收集入册，工具名冲突启动即失败（不静默覆盖）。
 * Agent 执行器与 /api/agent/tools 目录都只经本注册表取工具。
 */
@Component
public class ToolRegistry {

    private final Map<String, ButlerTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<ButlerTool> discovered) {
        for (ButlerTool tool : discovered) {
            ButlerTool prev = tools.putIfAbsent(tool.name(), tool);
            if (prev != null) {
                throw new IllegalStateException("工具名冲突：" + tool.name()
                        + "（" + prev.getClass().getName() + " 与 " + tool.getClass().getName() + "）");
            }
        }
    }

    public ButlerTool get(String name) {
        return name == null ? null : tools.get(name);
    }

    public List<ButlerTool> all() {
        return List.copyOf(tools.values());
    }

    /** 进系统提示词的工具目录文本。 */
    public String catalog() {
        StringBuilder sb = new StringBuilder("可用工具（args 按各工具的参数说明给 JSON 对象）：\n");
        for (ButlerTool tool : tools.values()) {
            sb.append("- ").append(tool.name()).append("：").append(tool.description())
                    .append(" 参数：").append(tool.argsHint()).append('\n');
        }
        return sb.toString();
    }
}
