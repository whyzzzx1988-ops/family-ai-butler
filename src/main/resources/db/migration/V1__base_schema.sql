-- 三合一家庭 AI 管家 · 底座 8 张核心表（V1）
-- 健康相关表不在本期：健康模块开工时独立 schema + 字段级加密另行设计。

create extension if not exists vector;

create table family (
    id          bigserial primary key,
    name        varchar(100) not null,
    plan        varchar(20)  not null default 'free',
    created_at  timestamptz  not null default now()
);

create table member (
    id          bigserial primary key,
    family_id   bigint       not null references family(id),
    role        varchar(20)  not null,  -- self / spouse / child / elder
    nickname    varchar(50)  not null,
    avatar      varchar(255),
    created_at  timestamptz  not null default now()
);
create index idx_member_family on member(family_id);

create table account (
    id              bigserial primary key,
    member_id       bigint       not null references member(id),
    login_type      varchar(20)  not null,  -- password / wechat
    credential_hash varchar(255),
    created_at      timestamptz  not null default now()
);
create index idx_account_member on account(member_id);

create table memory_item (
    id          bigserial primary key,
    family_id   bigint       not null references family(id),
    member_id   bigint       not null references member(id),
    scope       varchar(20)  not null,  -- work / learn / health / common
    kind        varchar(30)  not null,  -- fact / preference / event / record
    content     text         not null,
    source      varchar(30)  not null,  -- voice / text / file / system
    embedding   vector(1536),
    expires_at  timestamptz,
    created_at  timestamptz  not null default now()
);
create index idx_memory_member_scope on memory_item(member_id, scope);

create table inbox_event (
    id          bigserial primary key,
    family_id   bigint       not null references family(id),
    member_id   bigint       not null references member(id),
    source      varchar(20)  not null,  -- voice / text / file
    raw_ref     varchar(255),
    text        text,
    parsed_type varchar(30),
    status      varchar(20)  not null default 'new',  -- new / routed / done / failed
    created_at  timestamptz  not null default now()
);
create index idx_inbox_family_status on inbox_event(family_id, status);

create table todo_task (
    id              bigserial primary key,
    family_id       bigint       not null references family(id),
    owner_member_id bigint       not null references member(id),
    title           varchar(255) not null,
    due_at          timestamptz,
    status          varchar(20)  not null default 'open',  -- open / done / dropped
    source_event_id bigint       references inbox_event(id),
    created_at      timestamptz  not null default now()
);
create index idx_todo_owner_status on todo_task(owner_member_id, status);

create table briefing (
    id          bigserial primary key,
    family_id   bigint       not null references family(id),
    date        date         not null,
    type        varchar(20)  not null,  -- morning / weekly
    content_md  text         not null,
    pushed_at   timestamptz,
    created_at  timestamptz  not null default now()
);
create unique index idx_briefing_family_date_type on briefing(family_id, date, type);

create table llm_call_log (
    id          bigserial primary key,
    family_id   bigint       references family(id),
    module      varchar(20)  not null,  -- core / work / learn / health
    model       varchar(60)  not null,
    tokens_in   int          not null default 0,
    tokens_out  int          not null default 0,
    cost        numeric(12,6) not null default 0,
    latency_ms  int,
    created_at  timestamptz  not null default now()
);
create index idx_llm_log_family on llm_call_log(family_id, created_at);
