-- D3：account 补登录标识列（与 PG 版 V2 完全一致，H2 通用语法）。
-- identifier 存用户名/手机号，(login_type, identifier) 唯一防重复注册。
alter table account add column identifier varchar(100);
create unique index idx_account_login on account(login_type, identifier);
