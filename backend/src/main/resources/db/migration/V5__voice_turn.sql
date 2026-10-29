-- A log of what was asked of the assistant and what it did, so a wrong action can be traced. It holds the typed
-- text and the reply and is trimmed after app.voice.history-days. Audio is never stored.
create table voice_turn (
    id          integer primary key autoincrement,
    at          text not null,
    source      text not null,
    transcript  text not null,
    reply       text not null,
    tools_used  text not null,
    outcome     text not null,
    model_ms    integer not null,
    tools_ms    integer not null
);

create index voice_turn_at on voice_turn (at);
