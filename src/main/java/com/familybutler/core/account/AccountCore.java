package com.familybutler.core.account;

import org.springframework.stereotype.Component;

/**
 * 账号与家庭空间（底座）：家庭、成员与数据隔离。
 * 鉴权按混合路线走 Sa-Token（见 AuthController），本包只自研 family_id 隔离这一层。
 * D3 落地：family / member / account 三表与建家庭、加成员接口。
 */
@Component
public class AccountCore {
    // TODO(D3): 家庭空间与成员管理接口
}
