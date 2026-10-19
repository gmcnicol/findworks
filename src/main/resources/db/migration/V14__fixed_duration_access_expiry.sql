-- Seven-day invitation and development-access validity is an elapsed 168-hour duration,
-- independent of the database session time zone and daylight-saving transitions.
alter table access_grants alter column expires_at set default now()+interval '168 hours';

-- Never extend an existing credential while removing the autumn-DST extra hour.
update access_grants
set expires_at=least(expires_at,coalesce(last_used_at,now())+interval '168 hours')
where revoked_at is null;

update invitations i
set expires_at=least(i.expires_at,coalesce(
    (select e.created_at from email_outbox e where e.invitation_id=i.id),
    i.delivered_at,
    now()
)+interval '168 hours')
where i.redeemed_at is null and i.revoked_at is null;
