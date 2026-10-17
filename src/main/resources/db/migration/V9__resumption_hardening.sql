-- Match the existing seven-day browser credential lifetime on the server too.
alter table access_grants add column expires_at timestamptz;
update access_grants set expires_at=coalesce(last_used_at,now())+interval '7 days';
alter table access_grants alter column expires_at set not null;
alter table access_grants alter column expires_at set default now()+interval '7 days';

alter table interview_sessions add column created_at timestamptz not null default now();
alter table missions add column last_activity_at timestamptz not null default now();
update missions m set last_activity_at=greatest(m.created_at,
  (select max(created_at) from mission_versions v where v.mission_id=m.id),
  (select max(e.created_at) from evidence e join interview_sessions s on s.id=e.session_id where s.mission_id=m.id));

create function touch_mission_activity(target_mission_id uuid) returns void language sql as $$
  update missions set last_activity_at=now() where id=target_mission_id
$$;

create or replace function touch_discovery_from_mission() returns trigger language plpgsql as $$
begin
  if tg_op='INSERT' or new is distinct from old then
    perform touch_mission_activity(new.mission_id);
  end if;
  return new;
end $$;

create or replace function touch_discovery_from_mission_record() returns trigger language plpgsql as $$
begin
  if tg_op='INSERT' or new is distinct from old then
    perform touch_discovery_activity(new.discovery_id);
  end if;
  return new;
end $$;

create or replace function touch_discovery_from_session() returns trigger language plpgsql as $$
begin
  if tg_op='INSERT' or new is distinct from old then
    perform touch_mission_activity(new.mission_id);
  end if;
  return new;
end $$;

create or replace function touch_discovery_from_invitation() returns trigger language plpgsql as $$
declare target_mission_id uuid;
begin
  if tg_op='INSERT' or new is distinct from old then
    select mission_id into target_mission_id from interview_sessions where id=new.session_id;
    perform touch_mission_activity(target_mission_id);
  end if;
  return new;
end $$;

create or replace function touch_discovery_from_evidence() returns trigger language plpgsql as $$
declare target_mission_id uuid;
begin
  select mission_id into target_mission_id from interview_sessions where id=new.session_id;
  perform touch_mission_activity(target_mission_id);
  return new;
end $$;

create or replace function touch_discovery_from_knowledge() returns trigger language plpgsql as $$
declare target_mission_id uuid;
begin
  if tg_op='INSERT' or new is distinct from old then
    select s.mission_id into target_mission_id from knowledge_items k
      join investigation_results r on r.id=k.result_id join interview_sessions s on s.id=r.session_id
      where k.id=new.knowledge_id;
    perform touch_mission_activity(target_mission_id);
  end if;
  return new;
end $$;

create or replace function touch_discovery_from_unresolved() returns trigger language plpgsql as $$
declare target_mission_id uuid;
begin
  if tg_op='INSERT' or new is distinct from old then
    select s.mission_id into target_mission_id from investigation_results r
      join interview_sessions s on s.id=r.session_id where r.id=new.result_id;
    perform touch_mission_activity(target_mission_id);
  end if;
  return new;
end $$;

create or replace function touch_discovery_from_package_version() returns trigger language plpgsql as $$
declare target_mission_id uuid;
begin
  if tg_op='INSERT' or new is distinct from old then
    select s.mission_id into target_mission_id from findings_packages p
      join interview_sessions s on s.id=p.session_id where p.id=new.package_id;
    perform touch_mission_activity(target_mission_id);
  end if;
  return new;
end $$;

-- Revocation is activity; merely opening a page or refreshing a stream is not.
create function touch_activity_on_access_change() returns trigger language plpgsql as $$
declare target_mission_id uuid;
begin
  if tg_op='INSERT' or new.revoked_at is distinct from old.revoked_at then
    select mission_id into target_mission_id from interview_sessions where id=new.session_id;
    perform touch_mission_activity(target_mission_id);
  end if;
  return new;
end $$;
create trigger access_change_touches_activity after insert or update on access_grants
for each row execute function touch_activity_on_access_change();
