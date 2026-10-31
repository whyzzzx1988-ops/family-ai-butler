package com.familybutler.core.voice;

import org.springframework.stereotype.Component;

/**
 * 语音管道（底座）：ASR 入（Paraformer）、TTS 出（seed-audio 链路，后续）。
 * D4 已落地 ASR：实现拆在 AsrClient（接口）+ DashScopeParaformerAsrClient
 * （Paraformer 实时版 WebSocket 推流），由 InboxController 调用、转写落 inbox_event。
 * 本类仅保留为包级说明锚点。
 */
@Component
public class VoiceCore {
}
