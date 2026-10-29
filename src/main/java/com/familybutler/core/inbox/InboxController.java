package com.familybutler.core.inbox;

import com.familybutler.core.account.ApiException;
import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.CurrentUser;
import com.familybutler.core.memory.MemoryService;
import com.familybutler.core.voice.AsrClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 统一收件箱入口（D4 落地，W3 补解析分发）：文本/语音都先落 inbox_event，家庭与成员从登录态反查。
 * 语音同步走 Paraformer 转写：短语音在响应里就带转写文本；ASR 没配 Key 或失败时
 * 音频照样落盘、事件置降级状态（asr_unconfigured / asr_failed），不丢输入。
 * 落库/转写回填时按规则打 parsed_type 标（InboxService.parseType），事件可一键转记忆（to-memory）。
 */
@RestController
@RequestMapping("/api/inbox")
public class InboxController {

    private static final Logger log = LoggerFactory.getLogger(InboxController.class);
    private static final long MAX_AUDIO_BYTES = 25L * 1024 * 1024;
    private static final Map<String, String> FORMAT_BY_EXT = Map.of(
            "wav", "wav", "mp3", "mp3", "pcm", "pcm", "aac", "aac",
            "m4a", "aac", "amr", "amr", "opus", "opus", "ogg", "opus");
    private static final Set<String> SUPPORTED_EXT = FORMAT_BY_EXT.keySet();

    private final InboxService inboxService;
    private final CurrentUser currentUser;
    private final AsrClient asrClient;
    private final MemoryService memoryService;
    private final Path audioDir;

    public InboxController(InboxService inboxService, CurrentUser currentUser, AsrClient asrClient,
                           MemoryService memoryService,
                           @Value("${butler.inbox.audio-dir:./data/audio}") String audioDir) {
        this.inboxService = inboxService;
        this.currentUser = currentUser;
        this.asrClient = asrClient;
        this.memoryService = memoryService;
        this.audioDir = Path.of(audioDir);
    }

    public record TextRequest(@NotBlank String text) {
    }

    /** 转记忆可选参数：不传走默认（scope=common、kind=event、visibility=private）。 */
    public record ToMemoryRequest(String scope, String kind, String visibility) {
    }

    @PostMapping("/text")
    public ResponseEntity<Map<String, Object>> text(@RequestBody @Valid TextRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        long eventId = inboxService.createEvent(me.familyId(), me.id(), "text", null,
                request.text(), InboxService.parseType(request.text()), "new");
        return ResponseEntity.status(HttpStatus.CREATED).body(eventBody(inboxService.getEvent(eventId), null));
    }

    @PostMapping("/voice")
    public ResponseEntity<Map<String, Object>> voice(@RequestParam("file") MultipartFile file,
                                                       @RequestParam(required = false) Integer sampleRate) {
        AccountService.MemberView me = currentUser.requireMember();
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("音频文件为空");
        }
        if (file.getSize() > MAX_AUDIO_BYTES) {
            throw ApiException.badRequest("音频文件超过 25MB");
        }
        String ext = extensionOf(file.getOriginalFilename());
        String format = FORMAT_BY_EXT.get(ext);
        if (format == null) {
            throw ApiException.badRequest("不支持的音频格式 ." + ext + "，支持：" + String.join("/", SUPPORTED_EXT));
        }
        byte[] audio;
        try {
            audio = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("音频读取失败：" + e.getMessage());
        }
        int effectiveSampleRate = sampleRate != null ? sampleRate
                : "wav".equals(format) ? wavSampleRate(audio) : 16000;

        String storedName = saveAudio(audio, ext);
        long eventId = inboxService.createEvent(me.familyId(), me.id(), "voice", storedName,
                null, "unknown", "asr_processing");

        if (!asrClient.isConfigured()) {
            inboxService.markStatus(eventId, "asr_unconfigured");
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(eventBody(inboxService.getEvent(eventId),
                    "ASR 未配置：启动应用前设置环境变量 DASHSCOPE_API_KEY（百炼 API Key），音频已保存，可配置后重跑转写"));
        }
        try {
            String transcript = asrClient.transcribe(audio, format, effectiveSampleRate);
            inboxService.completeTranscription(eventId, transcript, "new");
            return ResponseEntity.status(HttpStatus.CREATED).body(eventBody(inboxService.getEvent(eventId), null));
        } catch (ApiException e) {
            log.warn("语音转写失败 eventId={} : {}", eventId, e.getMessage());
            inboxService.markStatus(eventId, "asr_failed");
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(eventBody(inboxService.getEvent(eventId),
                    e.getMessage() + "（音频已保存，可稍后重跑转写）"));
        }
    }

    /** 我所在家庭最近的收件箱事件（转写文本在这里可见）。 */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam(defaultValue = "20") int limit) {
        AccountService.MemberView me = currentUser.requireMember();
        return ResponseEntity.ok(inboxService.listByFamily(me.familyId(), limit).stream()
                .map(e -> eventBody(e, null))
                .toList());
    }

    /**
     * 一键转记忆（W3）：把一条本家庭事件的文本落成长期记忆，补上「语音一句话 → 记忆」的最后一环。
     * 归属沿用事件的成员（内容是谁说的就记在谁名下），source 沿用事件来源（voice/text/file），
     * scope/kind/visibility 不传走默认 common/event/private（枚举校验在 MemoryService 里做）。
     * v0 口径：每次调用新建一条记忆（不防重），转错了在记忆列表里删掉即可。
     * 事件不存在/不属于本家庭 → 404；文本为空（如语音还没转写）→ 400 明示。
     */
    @PostMapping("/{id}/to-memory")
    public ResponseEntity<Map<String, Object>> toMemory(@PathVariable long id,
                                                          @RequestBody(required = false) ToMemoryRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        InboxService.EventView event = inboxService.getEvent(id);
        if (event == null || event.familyId() != me.familyId()) {
            throw ApiException.notFound("收件箱事件不存在或不属于本家庭");
        }
        if (event.text() == null || event.text().isBlank()) {
            throw ApiException.badRequest("该事件文本为空，无法转记忆（事件状态：" + event.status()
                    + "）。语音事件请先完成转写");
        }
        ToMemoryRequest req = request == null ? new ToMemoryRequest(null, null, null) : request;
        MemoryService.MemoryView created = memoryService.create(me.familyId(), event.memberId(),
                req.scope() == null || req.scope().isBlank() ? "common" : req.scope(),
                req.kind() == null || req.kind().isBlank() ? "event" : req.kind(),
                event.text(), event.source(),
                req.visibility() == null || req.visibility().isBlank() ? "private" : req.visibility(),
                null);
        Map<String, Object> body = memoryBody(created);
        body.put("eventId", event.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private String saveAudio(byte[] audio, String ext) {
        try {
            Files.createDirectories(audioDir);
            String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + "-" + UUID.randomUUID().toString().substring(0, 8) + "." + ext;
            Files.write(audioDir.resolve(name), audio);
            return name;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "音频保存失败：" + e.getMessage());
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 标准 WAV 头里采样率在偏移 24 的小端 int；解析不出就按 16000 兜底（v2 支持任意采样率，偏差可容忍）。 */
    private static int wavSampleRate(byte[] audio) {
        try {
            if (audio.length > 28 && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F') {
                int rate = (audio[24] & 0xFF) | ((audio[25] & 0xFF) << 8)
                        | ((audio[26] & 0xFF) << 16) | ((audio[27] & 0xFF) << 24);
                if (rate >= 1000 && rate <= 192000) {
                    return rate;
                }
            }
        } catch (RuntimeException ignored) {
            // 落到兜底
        }
        return 16000;
    }

    static Map<String, Object> memoryBody(MemoryService.MemoryView m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", m.id());
        body.put("familyId", m.familyId());
        body.put("memberId", m.memberId());
        body.put("scope", m.scope());
        body.put("kind", m.kind());
        body.put("content", m.content());
        body.put("source", m.source());
        body.put("visibility", m.visibility());
        body.put("expiresAt", m.expiresAt());
        body.put("createdAt", m.createdAt());
        return body;
    }

    static Map<String, Object> eventBody(InboxService.EventView e, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", e.id());
        body.put("familyId", e.familyId());
        body.put("memberId", e.memberId());
        body.put("source", e.source());
        body.put("rawRef", e.rawRef());
        body.put("text", e.text());
        body.put("parsedType", e.parsedType());
        body.put("status", e.status());
        body.put("createdAt", e.createdAt());
        if (note != null) {
            body.put("note", note);
        }
        return body;
    }
}
