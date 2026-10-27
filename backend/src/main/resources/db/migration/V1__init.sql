-- Tokens for the three kinds of clients: user (phone login), display (paired door screen), agent (Pi).
-- Only a SHA-256 hash of each token is stored.
create table credential (
    id           text primary key,
    kind         text not null check (kind in ('user', 'display', 'agent')),
    token_hash   text not null unique,
    label        text not null,
    created_at   text not null,
    last_used_at text,
    revoked_at   text
);

create table task (
    id           integer primary key autoincrement,
    title        text not null,
    due_date     text,
    done         integer not null default 0,
    created_at   text not null,
    completed_at text
);

create table setting (
    key        text primary key,
    value      text not null,
    updated_at text not null
);
