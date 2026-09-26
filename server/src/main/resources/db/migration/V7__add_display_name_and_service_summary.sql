-- Prompt 16 follow-up (project owner feedback, docs/PROMPTS.md): a real display name
-- instead of the email's local part for chat/member identity, and a cheap denormalized
-- service-time summary so the church list can show something useful without loading the
-- full (now-LAZY) serviceTimes collection per row.

ALTER TABLE users ADD COLUMN display_name character varying(255);
ALTER TABLE churches ADD COLUMN service_summary character varying(255);
