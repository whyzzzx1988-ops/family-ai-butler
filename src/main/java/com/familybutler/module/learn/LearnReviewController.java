package com.familybutler.module.learn;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复习接口（第四周 R1/R2）：今日待复习列表、抽查出题、回记自评结果。
 * 调度规则（固定三段 1/3/7 天 + 自评调档）全在 LearnService，这里只做登录校验与出入参。
 * memberId 不传 = 全家庭视角（家长看孩子们的到期情况），传了只看该成员。
 */
@RestController
@RequestMapping("/api/learn/review")
public class LearnReviewController {

    private final LearnService learnService;
    private final LearnController learnController;
    private final CurrentUser currentUser;

    public LearnReviewController(LearnService learnService, LearnController learnController,
                                 CurrentUser currentUser) {
        this.learnService = learnService;
        this.learnController = learnController;
        this.currentUser = currentUser;
    }

    public record QuizRequest(Long memberId, Integer limit) {
    }

    public record LogRequest(@NotNull Long recordId, @NotBlank String result) {
    }

    @GetMapping("/today")
    public ResponseEntity<List<Map<String, Object>>> today(@RequestParam(required = false) Long memberId) {
        AccountService.MemberView me = currentUser.requireMember();
        return ResponseEntity.ok(learnService.reviewToday(me.familyId(), memberId).stream()
                .map(learnController::recordBody)
                .toList());
    }

    @PostMapping("/quiz")
    public ResponseEntity<Map<String, Object>> quiz(@RequestBody(required = false) QuizRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        QuizRequest req = request == null ? new QuizRequest(null, null) : request;
        LearnService.QuizResult result = learnService.quiz(me.familyId(), req.memberId(),
                req.limit() == null ? 5 : req.limit());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(i -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recordId", i.recordId());
            row.put("kind", i.kind());
            row.put("question", i.question());
            row.put("reference", i.reference());
            return row;
        }).toList());
        if (result.llmLogId() != null) {
            body.put("llmLogId", result.llmLogId());
        }
        if (result.note() != null) {
            body.put("note", result.note());
        }
        return ResponseEntity.ok(body);
    }

    /** 回记自评：result 三档 mastered/fuzzy/forgot，调档规则见 LearnService。 */
    @PostMapping("/log")
    public ResponseEntity<Map<String, Object>> log(@RequestBody @Valid LogRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        LearnService.ReviewLogResult result = learnService.logReview(me.familyId(), request.recordId(),
                request.result());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("logId", result.logId());
        body.put("record", learnController.recordBody(result.record()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
