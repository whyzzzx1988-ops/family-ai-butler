package com.familybutler.core.account;

import cn.dev33.satoken.stp.StpInterface;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 权限数据源。家庭产品的「权限」不是后台菜单 RBAC：
 * 这里只给成员角色（self/spouse/child/elder），真正的数据边界靠 family_id 隔离校验。
 * D3 落地时按 loginId 查 member 表返回其 role。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    private final AccountService accountService;

    public StpInterfaceImpl(AccountService accountService) {
        this.accountService = accountService;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 家庭场景不做细粒度权限点，统一走角色 + family_id 校验
        return List.of();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // D3 落地：loginId 就是 memberId，返回其在家庭中的角色
        String role = accountService.roleOf(Long.parseLong(loginId.toString()));
        return role == null ? List.of() : List.of(role);
    }
}
