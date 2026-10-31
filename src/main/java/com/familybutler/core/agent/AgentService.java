package com.familybutler.core.agent;

import com.familybutler.core.llm.LlmGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 多步执行器：一轮指令内让模型按「输出 JSON 决策 → 执行工具 → 回喂结果」循环，
 * 直到模型给出 final 或步数耗尽。全部模型调用走 LlmGateway（module=agent 落账）；
 * 未配 Key 时网关按既有口径抛 503，不假装执行。
 *
 * <p>容错口径：模型输出非 JSON 时整段当最终答复；工具不存在/执行异常时把 error 回喂模型
 * 让它纠错或如实转告，不中断整轮；步数耗尽仍无 final 时，再调一次模型强制总结
 * （该次调用失败则返回明示未完成 + 已执行轨迹）。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final int DEFAULT_MAX_STEPS = 4;
    private static final int MAX_STEPS_CAP = 6;
    private static final int RESULT_IN_PROMPT_LIMIT = 3000;
    private static final int RESULT_PREVIEW_LIMIT = 300;

    private static final String SYSTEM_PROMPT = """
            你是「全家管家」的智能编排器。用户给你一条指令，你通过调用工具获取家庭的真实数据或执行操作，最后给出答复。
            规则：
            1. 每一轮只输出一个 JSON 对象，不要输出任何其他文字，也不要用 Markdown 代码围栏包裹。
            2. 调用工具输出：{"tool":"工具名","args":{参数对象}}；结束并答复输出：{"final":"给用户的最终答复"}。
            3. 只能依据工具返回的事实作答，不编造数据；工具返回 found=false、空列表或 error 时，如实转告用户。
            4. 信息足够时立即用 final 结束，不要用相同参数重复调用同一个工具。
            5. 最终答复用简洁中文；涉及条目时列清要点。
            """;

    private final LlmGateway llmGateway;
    private final ToolRegistry registry;
    private final ObjectMapper mapper;

    public AgentService(LlmGateway llmGateway, ToolRegistry registry, ObjectMapper mapper) {
        this.llmGateway = llmGateway;
        this.registry = registry;
        this.mapper = mapper;
    }

    /** 一步工具调用轨迹（回给前端展示，result 只留预览，全文只进提示词）。 */
    public record StepTrace(int step, String tool, String argsJson, String resultPreview, boolean ok) {
    }

    public record RunResult(String answer, List<StepTrace> steps, List<Long> llmLogIds, int toolCalls) {
    }

    public RunResult run(long familyId, long memberId, String instruction, Integer maxSteps) {
        int budget = maxSteps == null ? DEFAULT_MAX_STEPS
                : Math.max(1, Math.min(maxSteps, MAX_STEPS_CAP));
        ButlerTool.ToolContext ctx = new ButlerTool.ToolContext(familyId, memberId);
        StringBuilder transcript = new StringBuilder("用户指令：").append(instruction).append('\n');
        List<StepTrace> steps = new ArrayList<>();
        List<Long> llmLogIds = new ArrayList<>();

        for (int step = 1; step <= budget; step++) {
            LlmGateway.ChatResult result = llmGateway.chat(familyId, "agent",
                    SYSTEM_PROMPT + "\n" + registry.catalog(),
                    transcript + "\n请输出下一步 JSON（工具调用或 final）。");
            llmLogIds.add(result.logId());
            String content = result.content() == null ? "" : result.content().trim();
            JsonNode decision = parseJsonLoose(content);
            if (decision == null || !decision.isObject()) {
                // 模型没按协议输出：整段文本当最终答复，不崩
                return new RunResult(content, steps, llmLogIds, steps.size());
            }
            JsonNode finalNode = decision.get("final");
            if (finalNode != null && finalNode.isTextual()) {
                return new RunResult(finalNode.asText(), steps, llmLogIds, steps.size());
            }
            JsonNode toolNode = decision.get("tool");
            if (toolNode == null || !toolNode.isTextual()) {
                return new RunResult(content, steps, llmLogIds, steps.size());
            }
            String toolName = toolNode.asText().trim();
            JsonNode args = decision.get("args");
            if (args == null || !args.isObject()) {
                args = mapper.createObjectNode();
            }
            String argsJson = args.toString();
            String resultJson;
            boolean ok;
            ButlerTool tool = registry.get(toolName);
            if (tool == null) {
                resultJson = "{\"error\":\"未知工具：" + toolName + "，请从工具目录里选\"}";
                ok = false;
            } else {
                try {
                    Object out = tool.execute(ctx, args);
                    resultJson = mapper.writeValueAsString(out);
                    ok = !(out instanceof java.util.Map<?, ?> map && map.containsKey("error"));
                } catch (Exception e) {
                    log.warn("工具 {} 执行异常：{}", toolName, e.getMessage());
                    resultJson = "{\"error\":\"工具执行失败：" + escape(e.getMessage()) + "\"}";
                    ok = false;
                }
            }
            steps.add(new StepTrace(step, toolName, argsJson, preview(resultJson), ok));
            transcript.append("\n第 ").append(step).append(" 步：调用工具 ").append(toolName)
                    .append("，参数 ").append(argsJson)
                    .append("\n工具结果：").append(truncate(resultJson, RESULT_IN_PROMPT_LIMIT)).append('\n');
        }

        // 步数耗尽：强制总结一次；再失败就明示未完成并附轨迹，不假装答完
        try {
            LlmGateway.ChatResult last = llmGateway.chat(familyId, "agent",
                    "你是「全家管家」的智能编排器。基于已执行的工具结果直接给出最终答复，只输出答复文本、不要输出 JSON、不要调用工具。",
                    transcript.toString());
            llmLogIds.add(last.logId());
            String answer = last.content() == null ? "" : last.content().trim();
            if (!answer.isEmpty()) {
                return new RunResult(answer, steps, llmLogIds, steps.size());
            }
        } catch (Exception e) {
            log.warn("Agent 强制总结失败：{}", e.getMessage());
        }
        return new RunResult("这轮指令在 " + budget + " 步内没有完成，已执行的步骤见轨迹，可以把指令拆细后重试。",
                steps, llmLogIds, steps.size());
    }

    /** 容忍代码围栏与前后杂文字的 JSON 提取（与 LearnService.readJsonLoose 同一口径）。 */
    private JsonNode parseJsonLoose(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = raw.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return mapper.readTree(json.substring(start, end + 1));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String truncate(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit) + "…（已截断）" : text;
    }

    private static String preview(String text) {
        return truncate(text, RESULT_PREVIEW_LIMIT);
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
