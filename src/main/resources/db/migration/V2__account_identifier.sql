-- D3：account 补登录标识列。V1 只有 member_id + login_type + credential_hash，
-- 没有「用什么登录」的标识，密码登录无从查起；identifier 存用户名/手机号，
-- (login_type, identifier) 唯一，防同一登录名重复注册。
alter table account add column identifier varchar(100);
create unique index idx_account_login on account(login_type, identifier);
