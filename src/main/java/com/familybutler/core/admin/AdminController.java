package com.familybutler.core.admin;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Web 管理页数据接口（第七周 N6）：页面本体是 static/admin.html 静态文件，
 * 这里只供一份家庭总览聚合数。登录态校验沿用 Sa-Token（未登录 401），家庭只认登录态反查。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final CurrentUser currentUser;

    public AdminController(AdminService adminService, CurrentUser currentUser) {
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        AccountService.MemberView me = currentUser.requireMember();
        return ResponseEntity.ok(adminService.overview(me.familyId()));
    }
}
