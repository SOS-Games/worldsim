-- Hibernate left a check constraint from the original WOOD/GOLD/FOOD inventory enum.

ALTER TABLE IF EXISTS agent_inventory DROP CONSTRAINT IF EXISTS agent_inventory_resource_type_check;
