-- Scenes: a named list of actions across devices. An action stores only the settings that are
-- included (JSON of the channel change); a setting that is absent is left alone when applied.
create table scene (
    id       integer primary key autoincrement,
    name     text not null,
    position integer not null,
    kind     text not null default 'custom' check (kind in ('custom', 'all_off'))
);

create unique index scene_name_unique on scene (lower(name));

create table scene_action (
    id        integer primary key autoincrement,
    scene_id  integer not null references scene (id) on delete cascade,
    device_id text not null references device (id) on delete cascade,
    channel   text not null,
    settings  text not null,
    position  integer not null,
    unique (scene_id, device_id, channel)
);

-- The built-in scene. It stores no actions: applying it switches off every present, reachable light.
insert into scene (name, position, kind) values ('All off', 0, 'all_off');

-- Quick access slots hold a light (device and channel) or a scene, exactly one of them. SQLite cannot
-- change these constraints in place, so the table is rebuilt; existing pins are lights.
create table quick_access_new (
    position  integer primary key check (position between 1 and 4),
    device_id text references device (id) on delete cascade,
    channel   text,
    scene_id  integer references scene (id) on delete cascade,
    check ((device_id is not null and channel is not null and scene_id is null)
        or (device_id is null and channel is null and scene_id is not null)),
    unique (device_id, channel),
    unique (scene_id)
);

insert into quick_access_new (position, device_id, channel, scene_id)
select position, device_id, channel, null from quick_access;

drop table quick_access;
alter table quick_access_new rename to quick_access;
