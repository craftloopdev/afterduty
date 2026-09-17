-- Track which billing rail set the user's current subscription:
--   'stripe' (web checkout), 'apple' (App Store IAP via RevenueCat),
--   'google' (Play Billing via RevenueCat). Null = legacy row / never paid.
-- Used to route "Manage subscription" to the right portal (Apple requires
-- native subscribers be sent to the App Store, not Stripe).
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS subscription_source VARCHAR(16);
