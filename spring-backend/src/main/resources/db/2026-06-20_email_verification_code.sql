-- db/2026-06-20_email_verification_code.sql  (DOCUMENTATION — applied by ddl-auto)
--
-- The backend runs `spring.jpa.hibernate.ddl-auto: update`, so the
-- `email_verification_code` table and its columns/indexes are AUTO-CREATED on
-- boot from the EmailVerificationCode JPA entity — there is no Flyway/Liquibase
-- in this project. This file records the equivalent DDL for the security
-- reviewer and for a controlled prod rollout (run it manually first if you want
-- the table created under change-control rather than at app startup).
--
-- Backs the passwordless email-code OTP flow
-- (docs/architecture/passwordless-otp-auth-spec.md §3.1 / §3.3).
--
-- Security-relevant columns:
--   code_hash   — SHA-256 hex of the 6-digit code; the plaintext code is NEVER
--                 stored, logged, or returned.
--   purpose     — 'SIGNIN' | 'ATTACH' (nullable for legacy grace).
--   attempts    — wrong-guess counter; the code is burned at 5.
--   expires_at  — created_at + 10 min; an expired code fails with a generic 400.
--   consumed_at — single-use marker (non-null ⇒ burned / superseded).
--   request_ip  — backs the DB-side per-IP hourly cap (survives Cloud Run
--                 multi-instance, where an in-memory counter would not).

CREATE TABLE IF NOT EXISTS email_verification_code (
  id          BIGSERIAL PRIMARY KEY,
  email       VARCHAR(320) NOT NULL,
  code_hash   VARCHAR(64)  NOT NULL,
  purpose     VARCHAR(16),
  attempts    INT          NOT NULL DEFAULT 0,
  expires_at  TIMESTAMP    NOT NULL,
  consumed_at TIMESTAMP,
  request_ip  VARCHAR(45),
  created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_evc_email_created   ON email_verification_code (email, created_at);
CREATE INDEX IF NOT EXISTS ix_evc_ip_created      ON email_verification_code (request_ip, created_at);
CREATE INDEX IF NOT EXISTS ix_evc_email_consumed  ON email_verification_code (email, consumed_at);
