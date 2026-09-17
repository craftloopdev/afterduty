-- V20260610__ai_call_log_is_batch.sql
-- Track which AI calls ran through a discounted batch lane (Anthropic Message
-- Batches = 50% of list price) so AiCostService books them at the rate
-- actually paid. Without this, batch jobs were booked at full list price and
-- Claude spend was overstated ~2x against the per-user usage cap.
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from JPA entities. This file exists so a future
-- Flyway switch has the canonical migration to apply. The AiCallLog entity has
-- been updated in lockstep so ddl-auto=update adds `is_batch` on next boot.

ALTER TABLE ai_call_logs
    ADD COLUMN is_batch BOOLEAN NULL;

-- Historical rows deliberately stay NULL: their cost columns were computed at
-- insert time under the old (inflated, full-list) price table, and AiCostService
-- never recomputes stored rows. Backfilling is_batch=true without recomputing
-- those stored dollars would make the flag contradict the costs next to it.
-- NULL therefore reads as "priced as realtime at the rates of its day"; only
-- rows written after this change carry an authoritative true/false.
