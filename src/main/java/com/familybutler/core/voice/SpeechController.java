package com.familybutler.core.voice;

import com.familybutler.core.account.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 小程序播报接口（TTS 出）：
 * GET /api/voice/tts/status —— 合成通道是否就位（未配 Key 时 configured=false，不报错）；
 * POST /api/voice/tts/plan —— 文本清洗 + 分段计划（逐段合成前先取计划）；
 * POST /api/voice/tts/segment —— 合成单个计划段，直接返回 audio/mpeg 字节流。
 * 全部接口要求登录；朗读正文不落库、不写日志。
 */
@RestController
@RequestMapping("/api/voice/tts")
public class SpeechController {

    private final SpeechService speechService;
    private final CurrentUser currentUser;

    public SpeechController(SpeechService speechService, CurrentUser currentUser) {
        this.speechService = speechService;
        this.currentUser = currentUser;
    }

    public record PlanRequest(@NotBlank @Size(max = 12000) String text) {
    }

    public record SegmentRequest(@NotBlank @Size(max = 1000) String text) {
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        currentUser.requireMember();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", speechService.isConfigured());
        body.put("provider", speechService.provider());
        body.put("model", speechService.model());
        body.put("maxSegmentChars", SpeechService.MAX_SEGMENT_CHARS);
        body.put("maxInputChars", SpeechService.MAX_INPUT_CHARS);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/plan")
    public ResponseEntity<Map<String, Object>> plan(@RequestBody @Valid PlanRequest request) {
        currentUser.requireMember();
        SpeechService.SpeechPlan plan = speechService.plan(request.text());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chunks", plan.chunks());
        body.put("chunkCount", plan.chunks().size());
        body.put("characterCount", plan.characterCount());
        body.put("estimatedDurationSeconds", plan.estimatedDurationSeconds());
        return ResponseEntity.ok(body);
    }

    @PostMapping(value = "/segment", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> segment(@RequestBody @Valid SegmentRequest request) {
        currentUser.requireMember();
        TtsClient.AudioResult result = speechService.synthesizeSegment(request.text());
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .cacheControl(CacheControl.noStore())
                .header("X-TTS-Provider", speechService.provider())
                .header("X-TTS-Model", speechService.model());
        if (result.durationSeconds() != null) {
            builder.header("X-TTS-Duration-Seconds", String.valueOf(result.durationSeconds()));
        }
        return builder.body(result.audio());
    }
}
