-- Who may use a room. 'open': everyone sees and controls its devices (what every room was until now).
-- 'view': everyone sees them, only the listed people control them. 'private': only the listed people see and
-- control them. The admin always can. A device with no room is open to everyone.
alter table room add column access text not null default 'open' check (access in ('open', 'view', 'private'));

create table room_member (
    room_id integer not null references room (id) on delete cascade,
    user_id integer not null references app_user (id) on delete cascade,
    primary key (room_id, user_id)
);
