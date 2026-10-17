delete from mission_origins where pointer = '/mission/expected_commitment_minutes';
alter table mission_versions drop column expected_minutes;
