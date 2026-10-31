package com.familybutler.core.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * TTS 配置（butler.tts.*）：seed-audio 的 API Key 只走环境变量 SEED_AUDIO_API_KEY。
 * 没配时状态接口 configured=false、播报接口 503 明示（与 LLM/ASR 未配置同口径）。
 */
@Component
@ConfigurationProperties(prefix = "butler.tts")
public class TtsProperties {

    private String apiKey = "";
    private String baseUrl = "https://openspeech.bytedance.com/api/v3";
    private String model = "seed-audio-1.0";
    private int sampleRate = 48000;
    private int timeoutSeconds = 120;
    private String voicePrompt = "只生成一段纯人声朗读，不要音乐、不要音效、不要添加原文之外的任何话。"
            + "用温和清晰、略带慵懒柔和感的成年女性普通话声音，以自然语速逐字朗读下面内容，并按标点自然停顿：";

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getVoicePrompt() {
        return voicePrompt;
    }

    public void setVoicePrompt(String voicePrompt) {
        this.voicePrompt = voicePrompt;
    }
}
