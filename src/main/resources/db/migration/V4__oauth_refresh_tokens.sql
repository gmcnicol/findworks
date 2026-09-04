create table oauth_refresh_tokens(
    hash char(64) primary key,
    grant_id uuid not null references oauth_grants(id) on delete cascade,
    expires_at timestamptz not null,
    redeemed_at timestamptz,
    revoked_at timestamptz
);
