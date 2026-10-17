update oauth_refresh_tokens r
set expires_at = 'infinity'
from oauth_grants g, oauth_clients c
where r.grant_id = g.id
  and g.client_id = c.id
  and r.redeemed_at is null
  and r.revoked_at is null
  and g.revoked_at is null
  and c.revoked_at is null;
