package com.familybutler.module.learn;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * inbox 一键转学习记录（第三周 L3）：路径挂在 /api/inbox 下与 to-memory 并列，
 * 但实现放在学习模块（依赖方向：模块用底座，不让底座反向依赖模块），逻辑全走 LearnService。
 * kind 不传按事件内容推断（错→mistake、单词/生词→word、其余 note）。
 */
@RestController
public class LearnInboxController {

    private final LearnService learnService;
    private final LearnController learnController;
    private final CurrentUser currentUser;

    public LearnInboxController(LearnService learnService, LearnController learnController,
                                CurrentUser currentUser) {
        this.learnService = learnService;
        this.learnController = learnController;
        this.currentUser = currentUser;
    }

    public record ToLearnRequest(String kind, String subject, Long childMemberId) {
    }

    @PostMapping("/api/inbox/{id}/to-learn")
    public ResponseEntity<Map<String, Object>> toLearn(@PathVariable long id,
                                                         @RequestBody(required = false) ToLearnRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        ToLearnRequest req = request == null ? new ToLearnRequest(null, null, null) : request;
        LearnService.CreateResult result = learnService.create(me.familyId(), me.id(), id,
                req.kind(), req.subject(), null, req.childMemberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(learnController.resultBody(result));
    }
}
