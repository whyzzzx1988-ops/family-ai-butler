-- 第四周 R1/R2：learn_record 补复习调度两列 + 新表 review_log。
-- review_stage：0/1/2 三段进行中、3 已毕业（不再到期）；间隔固定 1/3/7 天（拍板点①定版），
-- 自评只调档（mastered 进一段、fuzzy 原地、forgot 回第一段），规则在 LearnService 里可复核。
-- next_review_at 为空表示已毕业或历史数据（stage=0 且为空按「立即到期」处理，见复习队列查询口径）。
alter table learn_record add column review_stage int not null default 0;
alter table learn_record add column next_review_at timestamptz;

create table review_log (
    id          bigserial primary key,
    family_id   bigint       not null references family(id),
    member_id   bigint       not null references member(id),  -- 学习者（记录归属成员）
    record_id   bigint       not null references learn_record(id),
    result      varchar(20)  not null,  -- mastered / fuzzy / forgot（自评三档，拍板点②定版）
    created_at  timestamptz  not null default now()
);
create index idx_review_log_record on review_log(record_id, created_at);
