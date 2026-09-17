-- Phase D: add creator_user_id to atoms and enforce NOT NULL on intake_messages.thread_id

-- Add creator_user_id to atoms (nullable; existing rows stay null = "treat as owner")
ALTER TABLE atoms ADD COLUMN creator_user_id BIGINT REFERENCES users(id);

-- Apply NOT NULL constraint on thread_id now that all rows are backfilled (V20260516 did the backfill)
ALTER TABLE intake_messages ALTER COLUMN thread_id SET NOT NULL;
