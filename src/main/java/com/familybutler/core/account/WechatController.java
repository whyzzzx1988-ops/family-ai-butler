package com.familybutler.core.account;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信侧接口（第五周 A3 管道验证）：给当前登录成员的微信发一条测试订阅消息。
 * 前提：该成员已绑定微信、小程序端已 requestSubscribeMessage 订阅过对应模板、模板 ID 已配置；
 * 任一不满足时返回 ok=false 并写明卡在哪一步，方便按提审清单逐项对。
 */
@RestController
@RequestMapping("/api/wechat")
public class WechatController {

    private final WechatNotifyService notifyService;
    private final WechatProperties properties;
    private final CurrentUser currentUser;

    public WechatController(WechatNotifyService notifyService, WechatProperties properties, CurrentUser currentUser) {
        this.notifyService = notifyService;
        this.properties = properties;
        this.currentUser = currentUser;
    }

    public record NotifyTestRequest(String template) {
    }

    @PostMapping("/notify-test")
    public ResponseEntity<Map<String, Object>> notifyTest(@RequestBody(required = false) NotifyTestRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        String kind = request == null || request.template() == null ? "briefing" : request.template();
        String templateId;
        Map<String, String> data = new LinkedHashMap<>();
        String page;
        if ("todo".equals(kind)) {
            templateId = properties.getTemplateTodoId();
            data.put("thing1", "待办到期：测试待办事项");
            data.put("date2", LocalDate.now().toString());
            page = "pages/briefing/briefing";
        } else if ("medication".equals(kind)) {
            templateId = properties.getTemplateMedicationId();
            data.put("thing1", "该吃药了：测试药品一片");
            data.put("date2", LocalDate.now().toString());
            page = "pages/briefing/briefing";
        } else if ("review".equals(kind)) {
            templateId = properties.getTemplateReviewId();
            data.put("thing1", "今日有学习内容待复习");
            data.put("date2", LocalDate.now().toString());
            page = "pages/learn/learn";
        } else {
            templateId = properties.getTemplateBriefingId();
            data.put("thing1", "今日全家简报已生成");
            data.put("date2", LocalDate.now().toString());
            page = "pages/briefing/briefing";
        }
        WechatNotifyService.SendResult result = notifyService.sendToMember(me.id(), templateId, data, page);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", result.ok());
        body.put("message", result.message());
        return ResponseEntity.ok(body);
    }
}
