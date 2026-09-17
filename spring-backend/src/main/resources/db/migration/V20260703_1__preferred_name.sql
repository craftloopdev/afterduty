-- V20260703_1__preferred_name.sql
-- Profile identity: "What should we call you?" — a user-chosen display name,
-- set via PATCH /api/auth/me (trimmed, 1..60 chars) and surfaced on
-- GET /api/auth/me as "preferredName". Distinct from users.name, which is
-- derived at account creation (Firebase display name or the email local part
-- — for phone-OTP accounts that is the raw uid) and is NOT user-chosen.
-- Display precedence (preferredName over name) is a client concern; the API
-- exposes both.
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from JPA entities. This file exists so a future
-- Flyway switch has the canonical migration to apply. The User entity declares
-- the same column in lockstep, so ddl-auto=update adds it on next boot.

ALTER TABLE users ADD COLUMN IF NOT EXISTS preferred_name VARCHAR(60);
