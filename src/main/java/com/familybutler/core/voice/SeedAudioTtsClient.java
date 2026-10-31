package com.familybutler.core.voice;

import com.familybutler.core.account.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * 火山 OpenSpeech seed-audio-1.0 非流式合成客户端。
 * 协议与已实测的 seed_audio.py 同一条链：POST {base-url}/tts/create，
 * 请求头 X-Api-Key 鉴权，body 带 model / text_prompt / audio_config，
 * 响应顶层 audio 为 base64 MP3、duration 为秒。
 * 日志只记字数/耗时/字节数，绝不记朗读正文与密钥。
 */
@Component
public class SeedAudioTtsClient implements TtsClient {

    private static final Logger log = LoggerFactory.getLogger(SeedAudioTtsClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TtsProperties properties;
    private final HttpClient http;

    public SeedAudioTtsClient(TtsProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public String provider() {
        return "openspeech-seed-audio";
    }

    @Override
    public String model() {
        return properties.getModel();
    }

    @Override
    public AudioResult synthesize(String text) {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "TTS 未配置：请设置环境变量 SEED_AUDIO_API_KEY");
        }
        long startNanos = System.nanoTime();
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", properties.getModel());
            body.put("text_prompt", properties.getVoicePrompt() + "\n" + text);
            ObjectNode audioConfig = body.putObject("audio_config");
            audioConfig.put("format", "mp3");
            audioConfig.put("sample_rate", properties.getSampleRate());
            audioConfig.put("pitch_rate", 0);
            audioConfig.put("speech_rate", 0);
            audioConfig.put("loudness_rate", 0);
            body.putObject("watermark");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + "/tts/create"))
                    .timeout(Duration.ofSeconds(Math.max(10, properties.getTimeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", properties.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "语音合成失败（HTTP " + response.statusCode() + "）：" + errorDetail(response.body()));
            }
            AudioResult result = parseResponse(response.body());
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("TTS 合成完成：chars={} latencyMs={} audioBytes={} duration={}",
                    text.length(), latencyMs, result.audio().length, result.durationSeconds());
            return result;
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "语音合成调用被中断");
        } catch (Exception e) {
            log.warn("TTS 调用异常", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "语音合成调用异常：" + e.getMessage());
        }
    }

    /** 解析 seed-audio 响应（顶层 audio=base64、duration=秒）；缺音频即 502，不返回空文件充数。 */
    static AudioResult parseResponse(String responseBody) throws Exception {
        JsonNode root = MAPPER.readTree(responseBody == null ? "" : responseBody);
        String audioBase64 = root.path("audio").asText("");
        if (audioBase64.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "语音合成未返回音频数据：" + errorDetail(responseBody));
        }
        byte[] audio = Base64.getDecoder().decode(audioBase64);
        if (audio.length < 128) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "语音合成返回的音频过短（" + audio.length + " 字节）");
        }
        JsonNode duration = root.get("duration");
        Double durationSeconds = duration != null && duration.isNumber() ? duration.asDouble() : null;
        return new AudioResult(audio, "mp3", durationSeconds);
    }

    private static String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "无响应内容";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            String message = root.path("message").asText("");
            if (message.isBlank()) {
                message = root.path("error").path("message").asText("");
            }
            if (message.isBlank()) {
                message = root.path("error").asText("");
            }
            if (!message.isBlank()) {
                return truncate(message, 300);
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体走原文截断
        }
        return truncate(body, 300);
    }

    private static String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String truncate(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit) : text;
    }
}
