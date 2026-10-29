-- W1（第二周）：工作模块第一张业务表 meeting_note —— 一条 inbox_event 对应一份纪要，
-- event_id 唯一约束保证来源一对一可溯；family_id / member_id 沿用底座隔离口径，
-- 归属只从登录态反查，接口不接受前端传值。
create table meeting_note (
    id          bigserial primary key,
    family_id   bigint       not null references family(id),
    member_id   bigint       not null references member(id),
    event_id    bigint       not null references inbox_event(id),
    title       varchar(255) not null,
    content_md  text         not null,
    created_at  timestamptz  not null default now()
);
create unique index idx_meeting_note_event on meeting_note(event_id);
create index idx_meeting_note_family on meeting_note(family_id, created_at);
