package com.familybutler.core.voice;

/**
 * ASR 转写接口：音频字节进、转写文本出。
 * 当前实现是 DashScope Paraformer 实时版（WebSocket 推流，不要求音频有公网 URL）；
 * 后续换供应商（火山/本地引擎）只换实现类，inbox 管道不动。
 */
public interface AsrClient {

    /** 是否已配置好（如 API Key 就位）。未配置时上层走降级状态而不是硬报错。 */
    boolean isConfigured();

    /**
     * 同步转写一小段音频（家庭场景的语音留言，通常 < 60 秒）。
     *
     * @param audio     完整音频字节
     * @param format    paraformer 支持的格式：wav / mp3 / pcm / aac / amr / opus
     * @param sampleRate 采样率 Hz（paraformer-realtime-v2 任意采样率）
     * @return 转写文本（可能为空字符串，表示没听清内容）
     */
    String transcribe(byte[] audio, String format, int sampleRate);
}
