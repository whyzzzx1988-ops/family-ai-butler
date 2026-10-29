package com.familybutler.core.voice;

import com.familybutler.core.account.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DashScope Paraformer 实时 ASR（WebSocket API）客户端。
 * 协议（阿里云官方文档 paraformer 客户端/服务端事件）：
 *   连接 wss://dashscope.aliyuncs.com/api-ws/v1/inference，请求头 Authorization: Bearer <API Key>
 *   → 发 run-task（model/format/sample_rate）→ 等 task-started
 *   → 二进制分片推音频 → 发 finish-task
 *   → result-generated 里 sentence_end=true 的 sentence.text 拼成最终转写
 *   → task-finished 结束；task-failed 带 error_code / error_message。
 * 用 JDK 内置 WebSocket + Jackson，不加新依赖。音频从服务端推流，不需要公网 URL，
 * 正适合本地调试与家庭自托管。
 */
@Component
public class DashScopeParaformerAsrClient implements AsrClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeParaformerAsrClient.class);
    private static final int CHUNK_BYTES = 3200;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String wsUrl;
    private final String model;
    private final int timeoutSeconds;

    public DashScopeParaformerAsrClient(
            @Value("${butler.asr.api-key:}") String apiKey,
            @Value("${butler.asr.ws-url:wss://dashscope.aliyuncs.com/api-ws/v1/inference}") String wsUrl,
            @Value("${butler.asr.model:paraformer-realtime-v2}") String model,
            @Value("${butler.asr.timeout-seconds:30}") int timeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.wsUrl = wsUrl;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public String transcribe(byte[] audio, String format, int sampleRate) {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ASR 未配置：请设置环境变量 DASHSCOPE_API_KEY");
        }
        String taskId = UUID.randomUUID().toString();
        Session session = new Session(taskId, format, sampleRate);
        HttpClient client = HttpClient.newHttpClient();
        WebSocket webSocket = null;
        try {
            webSocket = client.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(wsUrl), session)
                    .get(15, TimeUnit.SECONDS);

            if (!session.started.await(10, TimeUnit.SECONDS)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ASR 任务未在预期时间内启动" + session.failureSuffix());
            }
            if (session.failure.get() != null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ASR 识别失败：" + session.failure.get());
            }
            // 分片推流：文件回放不用等实时节奏，轻微节流避免灌爆服务端缓冲
            for (int offset = 0; offset < audio.length; offset += CHUNK_BYTES) {
                int len = Math.min(CHUNK_BYTES, audio.length - offset);
                webSocket.sendBinary(ByteBuffer.wrap(audio, offset, len), true).get(10, TimeUnit.SECONDS);
                Thread.sleep(2);
            }
            webSocket.sendText(finishTaskJson(taskId), true).get(10, TimeUnit.SECONDS);

            if (!session.finished.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "ASR 转写超时" + session.failureSuffix());
            }
            if (session.failure.get() != null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ASR 识别失败：" + session.failure.get());
            }
            return session.transcript();
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASR 调用被中断");
        } catch (Exception e) {
            log.warn("ASR 调用异常", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASR 调用异常：" + e.getMessage());
        } finally {
            if (webSocket != null) {
                webSocket.abort();
            }
        }
    }

    private String runTaskJson(String taskId, String format, int sampleRate) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode header = root.putObject("header");
        header.put("action", "run-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");
        ObjectNode payload = root.putObject("payload");
        payload.put("task_group", "audio");
        payload.put("task", "asr");
        payload.put("function", "recognition");
        payload.put("model", model);
        ObjectNode params = payload.putObject("parameters");
        params.put("format", format);
        params.put("sample_rate", sampleRate);
        params.put("semantic_punctuation_enabled", true);
        ArrayNode hints = params.putArray("language_hints");
        hints.add("zh");
        hints.add("en");
        payload.putObject("input");
        return mapper.writeValueAsString(root);
    }

    private String finishTaskJson(String taskId) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode header = root.putObject("header");
        header.put("action", "finish-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");
        root.putObject("payload").putObject("input");
        return mapper.writeValueAsString(root);
    }

    /** 单次识别的会话状态：监听 WebSocket 事件，攒最终句、记失败原因。 */
    private final class Session implements WebSocket.Listener {
        private final String taskId;
        private final String format;
        private final int sampleRate;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<String> failure = new AtomicReference<>();
        private final StringBuilder transcript = new StringBuilder();
        private final StringBuilder partial = new StringBuilder();

        private Session(String taskId, String format, int sampleRate) {
            this.taskId = taskId;
            this.format = format;
            this.sampleRate = sampleRate;
        }

        String transcript() {
            synchronized (transcript) {
                return transcript.toString();
            }
        }

        String failureSuffix() {
            String f = failure.get();
            return f == null ? "" : "（" + f + "）";
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            try {
                webSocket.sendText(runTaskJson(taskId, format, sampleRate), true);
            } catch (Exception e) {
                failure.set("run-task 发送失败：" + e.getMessage());
                started.countDown();
                finished.countDown();
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            partial.append(data);
            if (!last) {
                return null;
            }
            String message = partial.toString();
            partial.setLength(0);
            try {
                JsonNode root = mapper.readTree(message);
                String event = root.path("header").path("event").asText("");
                switch (event) {
                    case "task-started" -> started.countDown();
                    case "result-generated" -> {
                        JsonNode sentence = root.path("payload").path("output").path("sentence");
                        boolean heartbeat = sentence.path("heartbeat").asBoolean(false);
                        boolean sentenceEnd = sentence.path("sentence_end").asBoolean(false);
                        if (!heartbeat && sentenceEnd) {
                            synchronized (transcript) {
                                transcript.append(sentence.path("text").asText(""));
                            }
                        }
                    }
                    case "task-finished" -> finished.countDown();
                    case "task-failed" -> {
                        String code = root.path("header").path("error_code").asText("");
                        String msg = root.path("header").path("error_message").asText("");
                        failure.set((code + " " + msg).trim());
                        started.countDown();
                        finished.countDown();
                    }
                    default -> log.debug("ASR 未处理事件: {}", event);
                }
            } catch (Exception e) {
                log.warn("ASR 事件解析失败: {}", message, e);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failure.set(String.valueOf(error.getMessage()));
            started.countDown();
            finished.countDown();
        }
    }
}
