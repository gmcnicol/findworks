# Clerk identity and returning to work

## Specified behaviour

Both researchers (Investigators) and interviewees use **Clerk's standard login/signup UI**, with ordinary sign-in configured for **email verification codes, without passwords**. One account can do both. There is no exclusive role selection or second login.

After sign-in, `/work` shows owned Discoveries and Missions when the account has an active research membership, and all interviews explicitly assigned to the account. Saved work is stored in PostgreSQL, not browser storage or Pi. Interviewees can sign out, close the browser, or change devices, sign in with the same account, and resume after their last accepted answer without finding or redeeming the invitation again.

Sign-in and signup mount Clerk's standard components. Clerk owns email-code entry, resend, recovery, bot protection, and native invitation-ticket acceptance; FindWorks verifies a fresh session token server-side before returning to the requested work. ClerkJS **6.37.0** and Clerk UI **1.38.1** are pinned. Email-code/passwordless policy is configured in the Clerk instance, not in a custom FindWorks form.

Email delivery queues a **native Clerk application invitation**, with `ignore_existing=true` for returning users and an opaque delivery correlation ID for recovery. Acceptance creates a new account only when needed. Its redirect returns to the exact FindWorks invitation, whose recipient, expiry, approved Mission version, and explicit Session assignment are still checked locally. The Clerk email link can authenticate its recipient: do not forward it. Manual FindWorks links remain available and require ordinary Clerk authentication.

The web process drains Clerk delivery/revocation jobs; the worker drains only invitation email jobs. Clerk credentials never enter the worker or Pi children. Lost-response/process-death retries recover a matching native invitation before sending again. Revoked, expired, or claimed local invitations close unused native tickets asynchronously; local interview denial is immediate even if the provider is unavailable.

## Security boundaries

- **Authentication:** validate each browser JWT's RS256 signature, issuer, expiration, issue time, session ID/status, and exact authorised party. The HTTP session stores CSRF state, not persistent Investigator or interview authentication.
- **Research permission:** existing local User, active Organisation membership, and Discovery ownership remain authoritative. Clerk signup does not create membership. First access may link an existing active User through the server-verified primary email without changing any ownership IDs. Subsequent research operations look up that immutable subject and recheck membership.
- **Interview permission:** claiming an invitation freshly retrieves the primary verified email through Clerk's backend, compares it to the recipient, locks the delivered/unexpired invitation and Session, and atomically persists the account assignment and redemption. Merely possessing a link, a secondary email, browser-supplied email claims, or an old browser grant cannot authorise an interview.
- **Dual roles:** revoking a research membership does not silently remove an independently granted interview assignment. Neither permission grants the other. An interviewee cannot see private Discovery context or findings.
- **MCP:** existing OAuth/PKCE tokens remain independent. Clerk does not decode them or replace their permissions.
- **Forms and streams:** CSRF remains required for cookie-authenticated writes. Every answer, revision, state fragment, and event-stream subscription checks the authenticated account against the Session ID in the route. Multiple interviews can be open independently.

An invitation is valid for seven days (an elapsed 168 hours) **before claim**. Claimed account access is not limited by that invitation or a seven-day (168-hour) browser cookie. Expired Clerk sessions require sign-in again, not invitation reissue. Explicit access revocation, reissue, Mission changes before start, and termination revoke assignments. These revocations are transactionally enforced by database triggers, including operator paths. Reissue preserves the Session/Evidence and cannot change a started Session's participant or approved Mission version. Deletion and purge are disabled for v1; legacy deletion-related triggers and cascading foreign keys remain inert.

Legacy seeded passwords and seven-day browser grants exist only in local development/test mode. Production enables Clerk and never falls back to these credentials.

## Configure the Clerk instance

In the Clerk Dashboard, enable:

1. Email addresses as the identifier and email verification codes for sign-in.
2. Passwordless email-code signup with email verification. Disable passwords and leave username, phone, and name fields off or optional.
3. The correct FindWorks development/production domains and permitted origins. Keep bot protection enabled; Clerk's standard UI handles its verification.

Clerk's components handle configured authentication steps. FindWorks accepts only a complete active session with a valid token; loading a component is never authentication.

Production Compose requires `CLERK_PUBLISHABLE_KEY`, `CLERK_SECRET_KEY`, `CLERK_FRONTEND_API`, `CLERK_ISSUER`, and `CLERK_JWKS_URI`. `CLERK_AUTHORIZED_PARTIES` is the exact FindWorks HTTPS origin, not a wildcard or trailing-slash URL. The instance's JWKS endpoint is normally `<Frontend API>/.well-known/jwks.json`; verify the actual Dashboard values. Keep the secret key only in the web process, never workers, browser code, logs, source control, or Pi children.

For local Clerk operation, export these variables and `FINDWORKS_CLERK_ENABLED=true`, then run `bash scripts/dev-up.sh`. The default disposable browser suite deliberately uses seeded development authentication. Its isolated Clerk client tests stub the SDK, not the security rules tested by the JVM suite.

## Preserve the pilot Investigator's existing work

Update the existing pilot User's email/name before its first Clerk login, **without replacing User or membership IDs**. This preserves existing Discoveries, Missions, Evidence, and OAuth ownership. A verified matching Clerk login then binds that User to the immutable subject. This is controlled provisioning, not public research membership enrolment.

```sql
-- Supply real values as psql variables, not source-controlled literals.
begin;
update users set email=:'investigator_email', display_name=:'investigator_name'
where id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' and clerk_user_id is null;
commit;
```

Additional research membership for an existing interview account must be explicitly provisioned using its Clerk subject; matching a historical cached email is not an automatic grant. Never unlink/rebind a User to work around an account-access problem.

Discovery Participants remain Discovery-scoped attribution records, not global identities merged by email. The explicit Session assignment connects each invited Participant to their authenticated account.

## Persisted resumption

`MissionResumeProjector` derives the next research action from lifecycle and review checkpoints, including incomplete reviews, approval, queued invitations, active account access, interviews, findings, and rejected findings follow-up. Page views and no-op updates do not advance activity.

Interview routes are `/interview/{sessionId}` with Session-scoped actions, fragments, and event streams. `/interview` redirects Clerk users to their saved interview list. The server reconstructs current state and accepted-answer history; resumption never resets the interview or creates duplicate Evidence. Only submitted/accepted answers are durable; unsent text is not autosaved. Product content deletion is disabled for v1.

Flyway V8/V9 established earlier continuity and development grant expiry. **V10** adds shared Clerk accounts, explicit interview assignments, interview recency, and atomic revocation. **V11** adds provider routing and native invitation/revocation tracking to the encrypted email outbox. Existing queued email defaults to its original provider. These are forward migrations; no existing Evidence or Discovery ownership is recreated or dropped.

Upgrade the web process first and wait for migrations/health before upgrading a worker with Flyway disabled. Older workers must not run alongside newly queued Clerk jobs: they do not understand provider routing. Preserve worker runtime/model configuration and keep Clerk keys only in the web environment.

## Verification

- `ClerkSecurityIntegrationTest`: real locally signed RSA tokens/local JWKS with only remote Clerk user lookup mocked. Covers first linking; both roles in one account; multiple interviews; a new browser/session resuming without an invitation or browser grant; preserved Evidence; membership checks; wrong-recipient/current-email validation; cross-account read/write/fragment/stream denial; expiry, revocation, termination, CSRF, and MCP separation.
- `ClerkUserClientTest`: verified primary email, account identity, banned/locked accounts, and fail-closed provider responses.
- Existing Mission, lifecycle, runtime, extraction, and browser tests continue to check checkpoint recovery and Evidence integrity.
- `ClerkInvitationClientTest` and `EmailDeliveryServiceIntegrationTest`: native invitations for existing/new users, safe redirects, encrypted queue/provider separation, lost-result recovery, leases, revocation, cancellation, and fail-closed delivery.
- `tests/e2e/clerk-client.spec.ts`: SDK-contract stubs for standard sign-in/signup mounting, native invitation modes, token refresh, exact return destination, account switching, and SDK/UI loading recovery. **Not live Clerk acceptance.**

### Live acceptance with configured keys

1. Preserve/provision the real pilot Investigator. Sign in with an emailed code and see their existing Discoveries/Missions at `/work`.
2. Send a Clerk invitation to a first-time account. Accept it in Clerk's standard signup screen, then explicitly claim the FindWorks invitation with its actual recipient email. Repeat with an existing account; verify there is no duplicate user and prior work remains.
3. Submit an answer, wait for the next question, and pause. Sign out and close the browser.
4. On a fresh browser/device, sign in by code without the invitation. Open the saved interview, resume, and verify the accepted answer appears exactly once.
5. Invite the researcher to an interview too. The same account sees research and interviews together, including multiple Sessions without cookie collisions.
6. Exercise expired Clerk sessions, deep links, wrong-account invitation recovery, account switching, logout, and original invitation expiry after claim.
7. Verify research membership revocation, explicit interview revocation, Session termination, and MCP consent/retrieval remain correctly separated.

Live email delivery, instance configuration, cookie refresh, and cross-device Clerk session behaviour require real instance credentials and this acceptance journey. Local signatures and SDK stubs do not prove them.

Primary references:

- https://clerk.com/docs/getting-started/quickstart/js-frontend
- https://clerk.com/docs/guides/users/inviting
- https://clerk.com/docs/guides/development/custom-flows/authentication/application-invitations
- https://clerk.com/docs/reference/backend/invitations/create-invitation
- https://clerk.com/docs/reference/backend/invitations/get-invitation-list
- https://clerk.com/docs/reference/backend/invitations/revoke-invitation
- https://clerk.com/docs/reference/objects/sign-in
- https://clerk.com/docs/reference/objects/sign-up
- https://clerk.com/docs/guides/sessions/manual-jwt-verification
- Published `@clerk/clerk-js@6.37.0` declarations for `Client`, `SignIn`, and `SignUp`.
