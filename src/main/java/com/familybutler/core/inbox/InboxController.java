package com.familybutler.core.inbox;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 统一收件箱入口：一切输入（语音/文本/文件）先落 inbox_event 再分发。
 * 骨架期文本入口先通，/voice 在 D4 接 ASR 后落地。
 */
@RestController
@RequestMapping("/api/inbox")
public class InboxController {

    public record TextInboxRequest(Long familyId, Long memberId, String text) {
    }

    @PostMapping("/text")
    public ResponseEntity<Map<String, Object>> text(@RequestBody TextInboxRequest request) {
        // TODO(D4): 落 inbox_event 并触发解析分发
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "parsedType", "unknown",
                "echo", request.text() == null ? "" : request.text()
        ));
    }
}
