package com.familybutler.core.agent;

import org.springframework.stereotype.Component;

/**
 * Agent 编排（底座）：任务拆解、工具注册与调用。
 * 思路平移自 DeepSeek Harness 插件化：业务模块以工具形式注册能力。
 * 已落地：实现拆在 ButlerTool（工具接口）+ ToolRegistry（注册表，名字冲突启动即失败）
 * + AgentService（多步执行器，走 LlmGateway 落账，module=agent）+ AgentController（接口），
 * 首批工具 GetBriefingTool / ListTodosTool / CreateTodoTool / SearchMemoriesTool /
 * ReviewTodayTool / HealthSummaryTool 均走既有 Service、家庭隔离口径与 REST 一致。
 * 本类仅保留为包级说明锚点。
 */
@Component
public class AgentCore {
}
