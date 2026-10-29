package com.familybutler.core.voice;

import org.springframework.stereotype.Component;

/**
 * 语音管道（底座）：ASR 入（Paraformer）、TTS 出（seed-audio 链路）。
 * D4 先打通 ASR：音频 -> 转写文本 -> 落 inbox_event。
 */
@Component
public class VoiceCore {
    // TODO(D4): ASR 转写接入
}
