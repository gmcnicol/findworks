-- Preserve existing queued email and isolate Clerk credentials to the web process.
alter table email_outbox add column delivery_provider text not null default 'EMAIL'
    check (delivery_provider in ('EMAIL','CLERK'));
alter table email_outbox add column provider_invitation_id text;
alter table email_outbox add column provider_revoked_at timestamptz;
create unique index email_outbox_clerk_invitation on email_outbox(provider_invitation_id)
    where provider_invitation_id is not null;
create index email_outbox_provider_pending on email_outbox(delivery_provider,available_at,created_at)
    where state in ('PENDING','RUNNING');
