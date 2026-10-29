package com.familybutler.core.account;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

/**
 * 当前登录成员解析（跨包共用）：checkLogin + 按 loginId 反查 member。
 * 所有要登录的底座接口都从这里拿 familyId / memberId，不信任前端传值。
 */
@Component
public class CurrentUser {

    private final AccountService accountService;

    public CurrentUser(AccountService accountService) {
        this.accountService = accountService;
    }

    /** 未登录抛 NotLoginException（→401）；成员不存在抛 401。 */
    public AccountService.MemberView requireMember() {
        StpUtil.checkLogin();
        return accountService.getMember(StpUtil.getLoginIdAsLong());
    }
}
