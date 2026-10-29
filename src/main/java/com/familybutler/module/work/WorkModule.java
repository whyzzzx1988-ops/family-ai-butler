package com.familybutler.module.work;

import org.springframework.stereotype.Component;

/**
 * 工作模块挂载点。第二周 W1 已落地：录音/文本事件 -> 纪要（MinutesService / MinutesController），
 * 待办候选抽取与确认流在 W2 接续。
 * 只能通过底座的记忆 / 提醒 / 模型网关接口协作，不直查其他模块的表；本模块只直写自己的表。
 */
@Component
public class WorkModule {
}
