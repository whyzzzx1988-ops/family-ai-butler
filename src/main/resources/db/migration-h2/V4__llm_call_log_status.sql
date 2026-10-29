-- D7：llm_call_log 补状态列（与 PG 版 V4 一致）。ok / error，失败调用也落账。
alter table llm_call_log add column status varchar(20) not null default 'ok';
