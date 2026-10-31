package com.familybutler.core.voice;

import com.familybutler.core.account.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 小程序播报编排：把任意文本（简报 Markdown 等）清洗成可朗读纯文本并切成短段，
 * 小程序逐段调合成接口、边播边备下一段。
 *
 * <p>分段口径：目标段长 {@link #PLAN_SEGMENT_CHARS} 字（中文约 4 字/秒，约 45 秒一段），
 * 硬上限 {@link #MAX_SEGMENT_CHARS} 字，始终远低于 seed-audio 单次 120 秒上限；
 * 切分优先落在句末标点，其次逗号，实在没有标点才按字数硬切。
 */
@Service
public class SpeechService {

    public static final int MAX_INPUT_CHARS = 8000;
    public static final int PLAN_SEGMENT_CHARS = 180;
    public static final int MAX_SEGMENT_CHARS = 240;

    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]*\\)");
    private static final Pattern NUMBERED_LIST = Pattern.compile("^\\d+[.、)]\\s*");
    private static final String SENTENCE_ENDS = "。！？；";
    private static final String SOFT_BREAKS = "，、：,";

    private final TtsClient ttsClient;

    public SpeechService(TtsClient ttsClient) {
        this.ttsClient = ttsClient;
    }

    public record SpeechPlan(List<String> chunks, int characterCount, int estimatedDurationSeconds) {
    }

    public boolean isConfigured() {
        return ttsClient.isConfigured();
    }

    public String provider() {
        return ttsClient.provider();
    }

    public String model() {
        return ttsClient.model();
    }

    /** 生成播报计划：清洗 + 分段。未配置 TTS 时 503 明示，不让前端白跑一轮。 */
    public SpeechPlan plan(String rawText) {
        requireConfigured();
        String plain = toPlainText(rawText);
        if (plain.isBlank()) {
            throw ApiException.badRequest("没有可播报的文本");
        }
        if (plain.length() > MAX_INPUT_CHARS) {
            throw ApiException.badRequest("播报文本过长（" + plain.length() + " 字，上限 " + MAX_INPUT_CHARS + " 字）");
        }
        List<String> chunks = splitIntoSegments(plain);
        // 中文朗读约 4 字/秒，只作前端展示的粗略预估
        int estimated = (int) Math.ceil(plain.length() / 4.0);
        return new SpeechPlan(chunks, plain.length(), estimated);
    }

    /** 合成一个计划段（或前端直传的一小段纯文本）；超硬上限直接 400，要求先分段。 */
    public TtsClient.AudioResult synthesizeSegment(String rawSegment) {
        requireConfigured();
        String segment = toPlainText(rawSegment);
        if (segment.isBlank()) {
            throw ApiException.badRequest("播报片段为空");
        }
        if (segment.length() > MAX_SEGMENT_CHARS) {
            throw ApiException.badRequest("单个播报片段过长（" + segment.length() + " 字，上限 "
                    + MAX_SEGMENT_CHARS + " 字），请先调用播报计划接口分段");
        }
        return ttsClient.synthesize(segment);
    }

    private void requireConfigured() {
        if (!ttsClient.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "TTS 未配置：请设置环境变量 SEED_AUDIO_API_KEY");
        }
    }

    /**
     * Markdown → 可朗读纯文本：去标题/列表/强调/链接等标记，逐行补句末标点形成自然停顿。
     * 包级可见以便单测。
     */
    static String toPlainText(String rawText) {
        if (rawText == null) {
            return "";
        }
        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String rawLine : normalized.split("\n")) {
            String line = rawLine.trim();
            if (line.equals("```")) {
                continue;
            }
            line = line.replaceAll("^#+\\s*", "");
            line = line.replaceAll("^>+\\s*", "");
            line = line.replaceAll("^[-*+]\\s+", "");
            line = NUMBERED_LIST.matcher(line).replaceFirst("");
            line = MARKDOWN_LINK.matcher(line).replaceAll("$1");
            line = line.replace("**", "").replace("__", "").replace("`", "")
                    .replace("*", "").replace("~", "");
            line = line.replace('·', '，').replace('|', '，').replace('/', '、');
            line = line.replaceAll("\\s+", " ").trim();
            // 中文标点两侧不留空格，朗读停顿更干净（如「简报 · 2026」→「简报，2026」）
            line = line.replaceAll("\\s*([，。！？；：、])\\s*", "$1");
            if (line.isEmpty()) {
                continue;
            }
            if (!endsWithPause(line)) {
                line += "。";
            }
            lines.add(line);
        }
        return String.join("\n", lines);
    }

    private static boolean endsWithPause(String line) {
        char last = line.charAt(line.length() - 1);
        return "。！？；：.!?;:".indexOf(last) >= 0;
    }

    /** 分段：先按句切，再贪心拼到目标段长；尾段过短时并回前段（不超硬上限）。 */
    static List<String> splitIntoSegments(String plainText) {
        List<String> sentences = new ArrayList<>();
        for (String line : plainText.split("\n")) {
            splitSentences(line, sentences);
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String piece = sentence;
            while (piece.length() > PLAN_SEGMENT_CHARS) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                int cut = findHardCut(piece, PLAN_SEGMENT_CHARS);
                chunks.add(piece.substring(0, cut).trim());
                piece = piece.substring(cut).trim();
            }
            if (piece.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.length() + piece.length() > PLAN_SEGMENT_CHARS) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            current.append(piece);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        // 尾段太短（如只有一句话的零头）并回前段，听感更连贯；合并后仍不超硬上限才并
        if (chunks.size() > 1) {
            int lastIndex = chunks.size() - 1;
            String last = chunks.get(lastIndex);
            String previous = chunks.get(lastIndex - 1);
            if (last.length() < 35 && previous.length() + last.length() <= MAX_SEGMENT_CHARS) {
                chunks.set(lastIndex - 1, previous + last);
                chunks.remove(lastIndex);
            }
        }
        return chunks;
    }

    private static void splitSentences(String line, List<String> out) {
        StringBuilder sentence = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            sentence.append(c);
            if (SENTENCE_ENDS.indexOf(c) >= 0) {
                out.add(sentence.toString());
                sentence.setLength(0);
            }
        }
        if (sentence.length() > 0) {
            out.add(sentence.toString());
        }
    }

    /** 长句硬切点：优先目标长度内最后一个软标点（逗号/顿号/冒号），没有就按目标长度切。 */
    private static int findHardCut(String piece, int target) {
        int limit = Math.min(target, piece.length());
        int searchFrom = Math.max(0, limit - 80);
        for (int i = limit - 1; i >= searchFrom; i--) {
            if (SOFT_BREAKS.indexOf(piece.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return limit;
    }
}
