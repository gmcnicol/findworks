alter table interview_sessions disable trigger interview_work_recency;

update interview_sessions
set state = 'COMPLETED'
where state = 'ENDED_EARLY';

alter table interview_sessions enable trigger interview_work_recency;

alter table interview_sessions
add constraint interview_sessions_no_ended_early
check (state <> 'ENDED_EARLY');
