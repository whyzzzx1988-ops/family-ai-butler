-- D7：llm_call_log 补状态列，失败的模型调用也要有账可查（ok / error），
-- 成本汇总时按 status 区分，别把失败调用混进成功成本。
alter table llm_call_log add column status varchar(20) not null default 'ok';
