-- Which room a paired door screen is in, so "the light" and "all the lights" said to it mean that room's lights.
-- Only displays use it; a phone moves around.
alter table credential add column room_id integer references room (id) on delete set null;
