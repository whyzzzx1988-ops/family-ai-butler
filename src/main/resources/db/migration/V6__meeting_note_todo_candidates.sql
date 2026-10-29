-- W2（第二周）：meeting_note 补待办候选两列（清单拍板走附属 JSON 字段先跑通，不单建候选表）。
-- todo_candidates：纪要生成同轮抽出的候选 JSON 数组 [{title, ownerHint, dueHint}]，线索只存原文表述，不解析成日期；
-- todo_confirmed_indexes：已确认转正式待办的候选下标 JSON 数组（如 [0,2]），防同一候选重复确认建重待办。
alter table meeting_note add column todo_candidates text;
alter table meeting_note add column todo_confirmed_indexes text;
