-- 第七周 N5（工作 P1）：待办到期推送防重复标记 + 纪要模板留痕。
alter table todo_task add column due_notified_at timestamp with time zone;
alter table meeting_note add column template varchar(20) not null default 'standard';
