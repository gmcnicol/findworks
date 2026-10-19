create or replace function touch_discovery_activity(target_discovery_id uuid)
returns void language sql as $$
  update discoveries set last_activity_at=now()
  where id=target_discovery_id and deleted_at is null
$$;

update access_grants g
set revoked_at=coalesce(g.revoked_at,now())
where g.revoked_at is null
  and exists(select 1 from interview_sessions s where s.id=g.session_id and s.state='TERMINATED');

update interview_account_access a
set revoked_at=coalesce(a.revoked_at,now())
where a.revoked_at is null
  and exists(select 1 from interview_sessions s where s.id=a.session_id and s.state='TERMINATED');

update invitations i
set state='REVOKED',revoked_at=coalesce(i.revoked_at,now())
where i.state<>'REVOKED'
  and exists(select 1 from interview_sessions s where s.id=i.session_id and s.state='TERMINATED');

alter table interview_sessions disable trigger interview_work_recency;

update interview_sessions
set state = 'COMPLETED',
    completed_at = coalesce(completed_at, last_activity_at)
where state = 'TERMINATED';

alter table interview_sessions enable trigger interview_work_recency;

alter table interview_sessions
add constraint interview_sessions_no_terminated_state
check (state <> 'TERMINATED');

create or replace function keep_completed_interview_sessions_terminal()
returns trigger language plpgsql as $$
begin
  if old.state = 'COMPLETED' and new.state <> 'COMPLETED' then
    raise exception 'completed Interview Sessions are terminal' using errcode = 'check_violation';
  end if;
  return new;
end $$;

create trigger interview_sessions_completed_is_terminal
before update of state on interview_sessions
for each row execute function keep_completed_interview_sessions_terminal();

create or replace function revoke_account_on_session_close() returns trigger language plpgsql as $$
begin
  if new.deleted_at is not null then
    update interview_account_access set revoked_at=now() where session_id=new.id and revoked_at is null;
  end if;
  return new;
end $$;

drop trigger discovery_account_access_revocation on discoveries;
drop function revoke_account_on_discovery_delete();
