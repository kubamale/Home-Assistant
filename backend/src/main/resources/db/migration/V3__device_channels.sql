-- Devices have channels (a light bar's main and background lights). The capabilities of each channel
-- are stored as JSON so controls and pinned tiles exist while the device is offline.
alter table device add column channels text;

-- Quick access pins a channel, not a whole device. SQLite cannot change a unique constraint in place,
-- so the table is rebuilt; existing pins become the device's "main" channel.
create table quick_access_new (
    position  integer primary key check (position between 1 and 4),
    device_id text not null references device (id) on delete cascade,
    channel   text not null,
    unique (device_id, channel)
);

insert into quick_access_new (position, device_id, channel)
select position, device_id, 'main' from quick_access;

drop table quick_access;
alter table quick_access_new rename to quick_access;
