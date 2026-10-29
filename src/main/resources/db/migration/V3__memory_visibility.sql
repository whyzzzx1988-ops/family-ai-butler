-- D5：memory_item 补可见性列。scope 已用作领域标签（work/learn/health/common），
-- 隐私边界单靠 member_id 分不清「配偶能不能看」：visibility=private 仅本人，
-- family 为全家可见（如孩子学习、老人用药这类要进全家简报的内容）。默认 private。
alter table memory_item add column visibility varchar(20) not null default 'private';
create index idx_memory_family_visibility on memory_item(family_id, visibility);
