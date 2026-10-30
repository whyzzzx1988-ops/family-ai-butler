package com.familybutler.module.health;

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
 * inbox 一键转健康记录（第六周 B2）：路径挂在 /api/inbox 下与 to-memory / to-learn 并列，
 * 实现放在健康模块（依赖方向：模块用底座），逻辑全走 HealthService。
 * kind 不传按内容推断；memberId 不传归属事件成员（如妈妈说的话默认记妈妈名下，可显式改给长辈）。
 */
@RestController
public class HealthInboxController {

    private final HealthService healthService;
    private final HealthController healthController;
    private final CurrentUser currentUser;

    public HealthInboxController(HealthService healthService, HealthController healthController,
                                 CurrentUser currentUser) {
        this.healthService = healthService;
        this.healthController = healthController;
        this.currentUser = currentUser;
    }

    public record ToHealthRequest(String kind, Long memberId) {
    }

    @PostMapping("/api/inbox/{id}/to-health")
    public ResponseEntity<Map<String, Object>> toHealth(@PathVariable long id,
                                                          @RequestBody(required = false) ToHealthRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        ToHealthRequest req = request == null ? new ToHealthRequest(null, null) : request;
        HealthService.HealthView view = healthService.createFromEvent(me.familyId(), id,
                req.kind(), req.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(healthController.recordBody(view));
    }
}
