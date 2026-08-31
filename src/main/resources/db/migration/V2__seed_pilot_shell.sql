insert into organizations (id, slug, name, active, retention_days, never_reviewed_retention_days)
values
    ('11111111-1111-1111-1111-111111111111', 'pilot-org', 'FindWorks Pilot', true, 90, 180),
    ('22222222-2222-2222-2222-222222222222', 'other-org', 'Other Organisation', true, 90, 180);

insert into users (id, email, display_name, password_hash, email_verified, active)
values
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'gareth@example.com', 'Gareth', '{noop}findworks', true, true),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'inactive.user@example.com', 'Inactive User', '{noop}findworks', true, false),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'inactive.membership@example.com', 'Inactive Membership', '{noop}findworks', true, true),
    ('dddddddd-dddd-dddd-dddd-dddddddddddd', 'outsider@example.com', 'Outside Organisation', '{noop}findworks', true, true),
    ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', 'unverified@example.com', 'Unverified User', '{noop}findworks', false, true);

insert into memberships (id, organization_id, user_id, role, active)
values
    ('f1111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'INVESTIGATOR', true),
    ('f2222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'INVESTIGATOR', true),
    ('f3333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', 'cccccccc-cccc-cccc-cccc-cccccccccccc', 'INVESTIGATOR', false),
    ('f4444444-4444-4444-4444-444444444444', '22222222-2222-2222-2222-222222222222', 'dddddddd-dddd-dddd-dddd-dddddddddddd', 'INVESTIGATOR', true),
    ('f5555555-5555-5555-5555-555555555555', '11111111-1111-1111-1111-111111111111', 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', 'INVESTIGATOR', true);
