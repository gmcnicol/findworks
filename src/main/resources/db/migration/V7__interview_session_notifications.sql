create function notify_interview_session_change() returns trigger
language plpgsql
as $$
begin
    perform pg_notify('interview_session_changes', new.id::text);
    return new;
end;
$$;

create trigger interview_session_state_changed
after update of state on interview_sessions
for each row
when (old.state is distinct from new.state)
execute function notify_interview_session_change();

create function notify_interview_question_ready() returns trigger
language plpgsql
as $$
begin
    if new.active then
        perform pg_notify('interview_session_changes', new.session_id::text);
    end if;
    return new;
end;
$$;

create trigger interview_question_ready
after insert on questions
for each row
execute function notify_interview_question_ready();
