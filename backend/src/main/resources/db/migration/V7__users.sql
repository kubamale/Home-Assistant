-- People. Until now there was one shared password; now each person has an account, and the data that is
-- personal (tasks, pins, calendar, own scenes, the look of their wall display) belongs to one of them.
-- password_hash is empty for the admin, whose password stays the one configured for the server
-- (APP_PASSWORD_HASH), so an existing installation keeps working with the password it has.
create table app_user (
    id            integer primary key autoincrement,
    name          text not null,
    password_hash text,
    admin         integer not null default 0,
    created_at    text not null
);

create unique index app_user_name_unique on app_user (lower(name));

-- The person who set the home up owns everything that exists today.
insert into app_user (id, name, password_hash, admin, created_at)
values (1, 'Admin', null, 1, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));

-- A phone login or a paired display acts as one person. The agent acts as none. Deleting a person ends their
-- logins and displays with them. layout is only used by displays (see DisplayLayout).
alter table credential add column user_id integer references app_user (id) on delete cascade;
alter table credential add column layout text;
update credential set user_id = 1 where kind in ('user', 'display');

-- Tasks are personal.
alter table task add column user_id integer references app_user (id) on delete cascade;
update task set user_id = 1;
create index task_user on task (user_id);

-- A scene is shared (no owner) or personal. Existing scenes stay shared, so everyone keeps them.
alter table scene add column user_id integer references app_user (id) on delete cascade;

-- Quick access is personal: each person has their own four slots. SQLite cannot change a primary key in
-- place, so the table is rebuilt; existing pins become the first person's.
create table quick_access_new (
    user_id   integer not null references app_user (id) on delete cascade,
    position  integer not null check (position between 1 and 4),
    device_id text references device (id) on delete cascade,
    channel   text,
    scene_id  integer references scene (id) on delete cascade,
    primary key (user_id, position),
    check ((device_id is not null and channel is not null and scene_id is null)
        or (device_id is null and channel is null and scene_id is not null)),
    unique (user_id, device_id, channel),
    unique (user_id, scene_id)
);

insert into quick_access_new (user_id, position, device_id, channel, scene_id)
select 1, position, device_id, channel, scene_id from quick_access;

drop table quick_access;
alter table quick_access_new rename to quick_access;

-- A person's own calendar account. The password is stored encrypted (see SecretBox). A person without a row
-- here has no calendar of their own; the admin then falls back to the account configured for the server.
create table user_calendar (
    user_id    integer primary key references app_user (id) on delete cascade,
    url        text not null,
    username   text not null,
    password   text not null,
    calendars  text not null default '[]',
    updated_at text not null
);
