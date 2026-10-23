-- Keep unverified dietary requirements across turns, retries and process restarts.
ALTER TABLE local_agent_sessions ADD COLUMN pending_requirements JSON NULL;
