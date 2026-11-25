-- Preserve historical codes and bindings while opening existing installations.
-- Increment the version so stale administrator requests cannot restore the old phase.
UPDATE xl_beta_stage SET phase = 'open', version = version + 1, updated_at = UTC_TIMESTAMP(3)
 WHERE id = 1 AND phase = 'beta';
