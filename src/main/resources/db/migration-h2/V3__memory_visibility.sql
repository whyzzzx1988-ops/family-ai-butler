-- D5：memory_item 补可见性列（与 PG 版 V3 一致）。private 仅本人 / family 全家可见，默认 private。
alter table memory_item add column visibility varchar(20) not null default 'private';
create index idx_memory_family_visibility on memory_item(family_id, visibility);
