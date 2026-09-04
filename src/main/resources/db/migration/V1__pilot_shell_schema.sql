create extension if not exists pgcrypto;

create table organizations (
    id uuid primary key,
    slug text not null unique,
    name text not null,
    active boolean not null,
    retention_days integer not null,
    never_reviewed_retention_days integer not null,
    created_at timestamptz not null default now()
);

create table users (
    id uuid primary key,
    email text not null unique,
    display_name text not null,
    password_hash text not null,
    email_verified boolean not null,
    active boolean not null,
    created_at timestamptz not null default now()
);

create table memberships (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    user_id uuid not null references users(id),
    role text not null,
    active boolean not null,
    created_at timestamptz not null default now(),
    unique (organization_id, id),
    unique (organization_id, user_id),
    constraint memberships_role_check check (role in ('INVESTIGATOR'))
);

create table discoveries (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    owner_membership_id uuid not null,
    title text not null,
    objective text not null,
    created_at timestamptz not null default now(),
    archived_at timestamptz,
    constraint discoveries_owner_membership_fk
        foreign key (organization_id, owner_membership_id)
        references memberships (organization_id, id)
);

create index discoveries_owner_membership_idx on discoveries (owner_membership_id, created_at desc);
