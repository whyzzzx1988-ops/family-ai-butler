package com.familybutler.core.voice;

import org.springframework.stereotype.Component;

/**
 * 语音管道（底座）：ASR 入（Paraformer）、TTS 出（seed-audio 链路）。
 * D4 已落地 ASR：实现拆在 AsrClient（接口）+ DashScopeParaformerAsrClient
 * （Paraformer 实时版 WebSocket 推流），由 InboxController 调用、转写落 inbox_event。
 * TTS 已落地：实现拆在 TtsClient（接口）+ SeedAudioTtsClient（OpenSpeech seed-audio-1.0 非流式合成）
 * + SpeechService（文本清洗与分段）+ SpeechController（/api/voice/tts/*），
 * 小程序经 utils/speech.js 逐段合成、边播边备下一段；首个播报面是简报页。
 * 本类仅保留为包级说明锚点。
 */
@Component
public class VoiceCore {
}
