package com.familybutler.core.voice;

import com.familybutler.core.account.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechServiceTest {

    private static TtsClient fakeClient(boolean configured) {
        return new TtsClient() {
            @Override
            public boolean isConfigured() {
                return configured;
            }

            @Override
            public String provider() {
                return "test";
            }

            @Override
            public String model() {
                return "test-model";
            }

            @Override
            public AudioResult synthesize(String text) {
                return new AudioResult(new byte[256], "mp3", 1.0);
            }
        };
    }

    @Test
    void planStripsMarkdownAndAddsPauses() {
        SpeechService service = new SpeechService(fakeClient(true));
        SpeechService.SpeechPlan plan = service.plan(
                "# 全家简报 · 2026-11-01\n\n## 今日工作\n- 10:00 项目周会\n- 记得带合同");

        String joined = String.join("", plan.chunks());
        assertTrue(joined.contains("全家简报，2026-11-01。"), joined);
        assertTrue(joined.contains("10:00 项目周会。"), joined);
        assertFalse(joined.contains("#"), joined);
        assertFalse(joined.contains("- "), joined);
        // characterCount 按清洗后全文计（含行间换行），分段拼接会去掉换行，故比 joined 多 3
        String expectedPlain = "全家简报，2026-11-01。\n今日工作。\n10:00 项目周会。\n记得带合同。";
        assertEquals(expectedPlain.length(), plan.characterCount());
    }

    @Test
    void planChunksLongTextWithinHardLimit() {
        SpeechService service = new SpeechService(fakeClient(true));
        String sentence = "今天要复习英语单词和数学错题，还要整理书包准备明天的课程。";
        SpeechService.SpeechPlan plan = service.plan(sentence.repeat(40));

        assertTrue(plan.chunks().size() > 3, "长文本应切成多段：" + plan.chunks().size());
        for (String chunk : plan.chunks()) {
            assertTrue(chunk.length() <= SpeechService.MAX_SEGMENT_CHARS,
                    "单段超硬上限：" + chunk.length());
        }
        assertEquals(sentence.repeat(40), String.join("", plan.chunks()));
    }

    @Test
    void planRejectsBlankAndUnconfigured() {
        SpeechService service = new SpeechService(fakeClient(true));
        assertThrows(ApiException.class, () -> service.plan("  \n  "));

        SpeechService unconfigured = new SpeechService(fakeClient(false));
        ApiException e = assertThrows(ApiException.class, () -> unconfigured.plan("你好"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.status());
    }

    @Test
    void segmentRejectsOverlongText() {
        SpeechService service = new SpeechService(fakeClient(true));
        String tooLong = "字".repeat(SpeechService.MAX_SEGMENT_CHARS + 1);
        ApiException e = assertThrows(ApiException.class, () -> service.synthesizeSegment(tooLong));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
    }

    @Test
    void plainTextKeepsLinkTextOnly() {
        String plain = SpeechService.toPlainText("详情见 [项目页](https://example.com/a?b=1) 说明");
        assertEquals("详情见 项目页 说明。", plain);
    }

    @Test
    void splitMergesTinyTailIntoPreviousChunk() {
        String head = "长".repeat(200) + "。";
        String text = head + "短句。";
        List<String> chunks = SpeechService.splitIntoSegments(text);
        assertEquals(1, chunks.size());
        assertEquals(head + "短句。", chunks.get(0));
    }
}
