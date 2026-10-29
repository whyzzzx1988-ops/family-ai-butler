-- 第三周 L1：学习模块第一张业务表 learn_record —— 统一表 + kind 区分（拍板点①定版）。
-- kind：word 单词 / mistake 错题 / note 笔记；detail_json 存 L2 的 LLM 结构化加工结果（单词卡/错题解析），
-- 加工字段稳定后再考虑拆表。event_id 可空：从 inbox 事件建的记录回填来源，直接文本建的为空。
-- member_id 是归属成员（孩子或录入者本人），family_id 沿用底座隔离口径，只从登录态反查。
create table learn_record (
    id          bigserial primary key,
    family_id   bigint       not null references family(id),
    member_id   bigint       not null references member(id),
    event_id    bigint       references inbox_event(id),
    kind        varchar(20)  not null,
    subject     varchar(30),
    content     text         not null,
    detail_json text,
    source      varchar(20)  not null,  -- voice / text / file（沿事件来源，直接文本为 text）
    created_at  timestamptz  not null default now()
);
create index idx_learn_family_member on learn_record(family_id, member_id, created_at);
create index idx_learn_event on learn_record(event_id);
