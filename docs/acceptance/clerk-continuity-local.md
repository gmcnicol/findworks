# Local Clerk and continuity verification

Run: 2026-10-04, working tree based on `85484df`. Changes are uncommitted; unrelated interview/UI work and the existing pilot containers were preserved.

## Corrected requirement

**Both researchers and interviewees use Clerk's standard login/signup UI. Ordinary sign-in uses email verification codes; emailed interview invitations use native Clerk invitations. A single account can do both.** Accounts find their saved research and assigned interviews after signing out or changing browsers/devices; interview resumption does not depend on keeping a browser grant or invitation.

## Results

- `source scripts/use-java-25.sh; mvn clean test`: **84 passed**, no failures, errors, or skips.
- `COMPOSE_PROJECT_NAME=findworks-clerk-native-e2e FINDWORKS_E2E_PORT=8081 FINDWORKS_CLERK_ENABLED=false npm test`: **26 passed**.
- `git diff --check`: passed.
- The isolated disposable Compose containers/volume were removed after the browser run. Existing `findworks-pilot` containers/data were not replaced or restarted.

Clerk-enabled integration tests use real locally signed RSA JWTs and a local JWKS endpoint, mocking only the remote Clerk User lookup. Browser Clerk tests stub the SDK; the rest of the browser suite uses development authentication. These are complementary automated checks, **not live hosted Clerk acceptance**.

## Specified behaviour checked

- Interview-only accounts authenticate without being forced into research memberships.
- One Clerk account sees owned Discoveries/Missions and multiple assigned interviews together; no exclusive role selection or second account.
- Claiming an invitation checks the current server-verified primary email, not cached profile email, JWT email claims, or link possession alone. Wrong accounts cannot consume the invitation.
- The account assignment and single-use redemption persist atomically.
- A new browser/Clerk session resumes a paused interview with no invitation, Interview Access Grant cookie, or old FindWorks HTTP session. Accepted Evidence is retained exactly once.
- Claimed access survives invitation expiry and cannot transfer to a different Clerk subject that later controls the same email address.
- Other accounts cannot read the interview, submit an answer, load its state fragment, subscribe to its events, or see it in saved work.
- Research membership revocation stops research access but does not remove independently assigned interview permission.
- Interview access revocation, termination, and deletion stop account access without erasing retained Evidence prematurely.
- A provider failure while claiming returns a retryable 503 without consuming the invitation.
- Production rejects old browser grants as a substitute for Clerk authentication.
- Existing signature/issuer/party/time/session checks, CSRF protection, short-lived authentication, MCP separation, review checkpoint recovery, attribution, runtime/extraction recovery, and retention/deletion regressions pass.
- Browser SDK-contract tests mount Clerk's standard SignIn/SignUp components, preserve first-time/returning native invitation modes, verify fresh tokens before restoring the exact work destination, and cover account switching and SDK/UI loading recovery.
- Native delivery tests cover new/existing-account invitation parameters, safe redirects, encrypted provider-specific outbox routing, lost-response recovery, leases, cancellation, revocation, and failure without interview grants.
- The required Spring Boot REST-client starter is present; the Clerk-enabled application context starts with the real invitation-client constructor rather than hiding a missing builder behind User-client mocks.

## Live configuration and acceptance

Real Development-instance keys have now been entered privately and validated against the Backend API and public JWKS. Pinned ClerkJS and UI assets return HTTP 200. A real-SDK canary uses an isolated database and the provider's documented testing-token/test-email mechanism; it does not modify the pilot's ownership or Evidence. The pilot remains unactivated pending approval. Actual human email delivery and real-device acceptance are still outstanding; no deployment or issue closure is claimed.

Configure the Clerk instance for passwordless verified-email signup and email-code sign-in, supply its keys/origins, preserve the pilot Investigator's existing local identity, and execute the live journey in [`../clerk-and-continuity.md`](../clerk-and-continuity.md).

Existing pre-Clerk interviews retain their Sessions and Evidence. If their legacy invitation has already been consumed, the researcher reissues access once so the interviewee can establish the new account assignment. Subsequent return journeys do not require reissue.
