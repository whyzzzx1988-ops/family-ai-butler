package com.familybutler.core.account;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信小程序配置（butler.wechat.*）：AppID/Secret 只走环境变量 WECHAT_APPID / WECHAT_SECRET，
 * 没配时微信登录返回 503 明示（与 LLM/ASR 未配置同口径），密码登录不受影响。
 * 订阅消息模板 ID 在小程序公众平台申请后配（template-briefing-id / template-review-id）。
 */
@Component
@ConfigurationProperties(prefix = "butler.wechat")
public class WechatProperties {

    private String appid = "";
    private String secret = "";
    private String templateBriefingId = "";
    private String templateReviewId = "";
    private String templateMedicationId = "";

    public boolean isConfigured() {
        return appid != null && !appid.isBlank() && secret != null && !secret.isBlank();
    }

    public String getAppid() {
        return appid;
    }

    public void setAppid(String appid) {
        this.appid = appid;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getTemplateBriefingId() {
        return templateBriefingId;
    }

    public void setTemplateBriefingId(String templateBriefingId) {
        this.templateBriefingId = templateBriefingId;
    }

    public String getTemplateReviewId() {
        return templateReviewId;
    }

    public void setTemplateReviewId(String templateReviewId) {
        this.templateReviewId = templateReviewId;
    }

    public String getTemplateMedicationId() {
        return templateMedicationId;
    }

    public void setTemplateMedicationId(String templateMedicationId) {
        this.templateMedicationId = templateMedicationId;
    }
}
