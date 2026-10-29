-- W2（第二周）：meeting_note 补待办候选两列（H2 版，与 PG 版 V6 一致，text 类型通用）。
alter table meeting_note add column todo_candidates text;
alter table meeting_note add column todo_confirmed_indexes text;
