alter table interview_sessions add column last_activity_at timestamptz not null default now();
update interview_sessions s set last_activity_at=greatest(s.created_at,s.started_at,s.completed_at,
    (select max(e.created_at) from evidence e where e.session_id=s.id));
create function touch_interview_work() returns trigger language plpgsql as $$
begin
  if (new.state,new.revision,new.participant_id,new.mission_version)
     is distinct from (old.state,old.revision,old.participant_id,old.mission_version) then
    new.last_activity_at=now();
  end if;
  return new;
end $$;
create trigger interview_work_recency before update on interview_sessions
for each row execute function touch_interview_work();

create table clerk_accounts (
    clerk_user_id text primary key,
    verified_email text not null,
    created_at timestamptz not null default now()
);

create table interview_account_access (
    id uuid primary key,
    organization_id uuid not null,
    session_id uuid not null,
    clerk_user_id text not null references clerk_accounts(clerk_user_id),
    granted_at timestamptz not null default now(),
    revoked_at timestamptz,
    foreign key(session_id,organization_id) references interview_sessions(id,organization_id) on delete cascade
);
create unique index one_active_account_per_interview on interview_account_access(session_id) where revoked_at is null;
create index interview_account_work on interview_account_access(clerk_user_id,session_id) where revoked_at is null;

-- Every existing access-revocation path also revokes account access. Expiration of
-- a development browser cookie does not revoke a durable Clerk account assignment.
create function revoke_interview_account_access() returns trigger language plpgsql as $$
begin
  if new.revoked_at is not null and old.revoked_at is null then
    update interview_account_access set revoked_at=now() where session_id=new.session_id and revoked_at is null;
  end if;
  return new;
end $$;
create trigger browser_access_revocation_revokes_account after update on access_grants
for each row execute function revoke_interview_account_access();

create function revoke_account_on_session_close() returns trigger language plpgsql as $$
begin
  if new.deleted_at is not null or new.state='TERMINATED' then
    update interview_account_access set revoked_at=now() where session_id=new.id and revoked_at is null;
  end if;
  return new;
end $$;
create trigger session_access_revocation after update on interview_sessions
for each row execute function revoke_account_on_session_close();

create function revoke_account_on_discovery_delete() returns trigger language plpgsql as $$
begin
  if new.state<>'ACTIVE' then
    update interview_account_access set revoked_at=now()
      where session_id in(select id from interview_sessions where discovery_id=new.id) and revoked_at is null;
  end if;
  return new;
end $$;
create trigger discovery_account_access_revocation after update on discoveries
for each row execute function revoke_account_on_discovery_delete();

create function revoke_account_on_invitation_reissue() returns trigger language plpgsql as $$
begin
  if new.revoked_at is not null and old.revoked_at is null then
    update interview_account_access set revoked_at=now() where session_id=new.session_id and revoked_at is null;
  end if;
  return new;
end $$;
create trigger invitation_account_access_revocation after update on invitations
for each row execute function revoke_account_on_invitation_reissue();
