-- Allow additional resource types (STONE, IRON, HERBS, …).
-- Hibernate may have created a check constraint from the original enum.

ALTER TABLE IF EXISTS tile DROP CONSTRAINT IF EXISTS tile_resourcetype_check;
