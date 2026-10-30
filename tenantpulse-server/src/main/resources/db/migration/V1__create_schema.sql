-- The version counter. A single row; publishers lock it, so versions are handed out one at a time
-- and become visible in the same order they were assigned (version order == commit order).
create table config_version (
    id      smallint primary key check (id = 1),
    version bigint not null check (version >= 0)
);
insert into config_version (id, version) values (1, 0);

-- One row per published change set. Rows are never updated or deleted: this is the audit log.
create table change_set (
    version         bigint primary key,
    author          varchar(200) not null,
    reason          varchar(500) not null,
    idempotency_key varchar(200) unique,
    kind            varchar(32)  not null,
    ref_version     bigint,
    published_at    timestamptz  not null
);

-- The individual edits of a change set. A null raw_value removes that override.
create table setting_change (
    version     bigint       not null references change_set (version),
    seq         int          not null,
    setting_key varchar(128) not null,
    scope_level varchar(16)  not null,
    client_id   varchar(128),
    office_id   varchar(128),
    shift_id    varchar(128),
    raw_value   text,
    primary key (version, seq)
);

create index setting_change_client_idx on setting_change (client_id);
create index change_set_promotion_idx on change_set (ref_version) where kind = 'ROLLOUT_PROMOTE';
