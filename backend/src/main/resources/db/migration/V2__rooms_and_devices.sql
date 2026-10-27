-- Rooms and the devices the agent has reported. Live state (power, reachable) is not stored;
-- it is owned by the agent and merged in when devices are read.
create table room (
    id       integer primary key autoincrement,
    name     text not null,
    position integer not null
);

create unique index room_name_unique on room (lower(name));

create table device (
    id            text primary key,
    type          text not null,
    reported_name text not null,
    name_override text,
    room_id       integer references room (id) on delete set null,
    first_seen_at text not null,
    last_seen_at  text not null
);

create index device_room on device (room_id);

-- The dashboard's quick access slots. The primary key and the check make four slots a database rule.
create table quick_access (
    position  integer primary key check (position between 1 and 4),
    device_id text not null unique references device (id) on delete cascade
);
