alter table users add column clerk_user_id text;
create unique index users_clerk_user_id_unique on users(clerk_user_id) where clerk_user_id is not null;

create function touch_discovery_activity(target_discovery_id uuid) returns void language sql as $$
    update discoveries set last_activity_at=now() where id=target_discovery_id and state='ACTIVE'
$$;

create function touch_discovery_from_mission_record() returns trigger language plpgsql as $$
begin
  perform touch_discovery_activity(new.discovery_id);
  return new;
end $$;

create function touch_discovery_from_mission() returns trigger language plpgsql as $$
declare target_mission_id uuid; target_discovery_id uuid;
begin
  target_mission_id := new.mission_id;
  select discovery_id into target_discovery_id from missions where id=target_mission_id;
  perform touch_discovery_activity(target_discovery_id);
  return new;
end $$;

create function touch_discovery_from_session() returns trigger language plpgsql as $$
begin
  perform touch_discovery_activity(new.discovery_id);
  return new;
end $$;

create function touch_discovery_from_invitation() returns trigger language plpgsql as $$
declare target_session_id uuid; target_discovery_id uuid;
begin
  target_session_id := new.session_id;
  select discovery_id into target_discovery_id from interview_sessions where id=target_session_id;
  perform touch_discovery_activity(target_discovery_id);
  return new;
end $$;

create function touch_discovery_from_evidence() returns trigger language plpgsql as $$
declare target_session_id uuid; target_discovery_id uuid;
begin
  target_session_id := new.session_id;
  select discovery_id into target_discovery_id from interview_sessions where id=target_session_id;
  perform touch_discovery_activity(target_discovery_id);
  return new;
end $$;

create function touch_discovery_from_knowledge() returns trigger language plpgsql as $$
declare target_discovery_id uuid;
begin
  select s.discovery_id into target_discovery_id
  from knowledge_items k
  join investigation_results r on r.id=k.result_id
  join interview_sessions s on s.id=r.session_id
  where k.id=new.knowledge_id;
  perform touch_discovery_activity(target_discovery_id);
  return new;
end $$;

create function touch_discovery_from_unresolved() returns trigger language plpgsql as $$
declare target_discovery_id uuid;
begin
  select s.discovery_id into target_discovery_id
  from investigation_results r join interview_sessions s on s.id=r.session_id
  where r.id=new.result_id;
  perform touch_discovery_activity(target_discovery_id);
  return new;
end $$;

create function touch_discovery_from_package_version() returns trigger language plpgsql as $$
declare target_package_id uuid; target_discovery_id uuid;
begin
  target_package_id := new.package_id;
  select s.discovery_id into target_discovery_id
  from findings_packages p join interview_sessions s on s.id=p.session_id
  where p.id=target_package_id;
  perform touch_discovery_activity(target_discovery_id);
  return new;
end $$;

create trigger mission_touches_discovery after insert or update on missions
for each row execute function touch_discovery_from_mission_record();
create trigger mission_version_touches_discovery after insert or update on mission_versions
for each row execute function touch_discovery_from_mission();
create trigger mission_review_touches_discovery after insert or update on mission_review
for each row execute function touch_discovery_from_mission();
create trigger session_touches_discovery after insert or update on interview_sessions
for each row execute function touch_discovery_from_session();
create trigger invitation_touches_discovery after insert or update on invitations
for each row execute function touch_discovery_from_invitation();
create trigger evidence_touches_discovery after insert on evidence
for each row execute function touch_discovery_from_evidence();
create trigger knowledge_review_touches_discovery after insert or update on knowledge_versions
for each row execute function touch_discovery_from_knowledge();
create trigger unresolved_review_touches_discovery after insert or update on unresolved
for each row execute function touch_discovery_from_unresolved();
create trigger package_version_touches_discovery after insert or update on package_versions
for each row execute function touch_discovery_from_package_version();
