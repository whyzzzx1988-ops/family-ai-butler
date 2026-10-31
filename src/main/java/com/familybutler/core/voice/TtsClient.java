package com.familybutler.core.voice;

/**
 * TTS 语音合成接口：纯文本进、完整音频字节出。
 * 当前实现是火山 OpenSpeech seed-audio-1.0（非流式生成）；
 * 后续换供应商只换实现类，小程序播报接口与分段口径不动。
 */
public interface TtsClient {

    /** 是否已配置好（如 API Key 就位）。未配置时上层返回 503 明示，不假装合成。 */
    boolean isConfigured();

    /** 供应商标识（接口状态与响应头用，不含任何密钥信息）。 */
    String provider();

    /** 模型标识。 */
    String model();

    /**
     * 合成一小段文本。调用方负责把长文本切到单段上限内；
     * seed-audio 单次生成上限约 120 秒，超长会导致截断或失败。
     *
     * @param text 已清洗的纯文本（不带 Markdown）
     * @return 完整音频（当前为 MP3）及供应商返回的时长（可能为空）
     */
    AudioResult synthesize(String text);

    record AudioResult(byte[] audio, String format, Double durationSeconds) {
    }
}
