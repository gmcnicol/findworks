alter table runtime_runs
    add column available_at timestamptz not null default now();

create index runtime_runs_available
    on runtime_runs(state, available_at, created_at);

create table runtime_circuit_breakers(
    dependency text primary key,
    consecutive_failures int not null default 0,
    open_until timestamptz,
    last_error_code text,
    updated_at timestamptz not null default now()
);

insert into runtime_circuit_breakers(dependency)
values('MODEL_PROVIDER');
