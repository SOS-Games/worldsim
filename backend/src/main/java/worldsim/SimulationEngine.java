package worldsim;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SimulationEngine {

    @Inject
    PathfindingService pathfindingService;

    @Inject
    BehaviorService behaviorService;

    @Scheduled(every = "1s")
    @RunOnVirtualThread
    @Transactional
    public void tick() {
        for (Agent agent : Agent.all()) {
            updateAgent(agent);
            agent.persist();
        }
    }

    private void updateAgent(Agent agent) {
        if (agent.job == null) {
            return;
        }

        Tile current = Tile.findNearest(agent.location);
        if (current == null) {
            return;
        }

        // Deliver cargo in the city.
        if (current.isCity() && agent.inventoryCount() > 0) {
            agent.clearInventory();
            behaviorService.assignGoal(agent);
            replanPath(agent);
            return;
        }

        // Harvest while standing on a matching resource (stay until full or depleted).
        if (canHarvest(agent, current)) {
            extractResources(agent, current);
            if (agent.isInventoryFull()) {
                behaviorService.assignGoal(agent);
                replanPath(agent);
            }
            return;
        }

        // Ensure we have a sensible goal for the current inventory state.
        ensureGoal(agent);
        moveAlongPath(agent);

        Tile afterMove = Tile.findNearest(agent.location);
        if (afterMove != null && canHarvest(agent, afterMove)) {
            extractResources(agent, afterMove);
            if (agent.isInventoryFull()) {
                behaviorService.assignGoal(agent);
                replanPath(agent);
            }
        } else if (afterMove != null && afterMove.isCity() && agent.inventoryCount() > 0) {
            agent.clearInventory();
            behaviorService.assignGoal(agent);
            replanPath(agent);
        }
    }

    private void ensureGoal(Agent agent) {
        boolean shouldDeliver = agent.inventoryCount() >= BehaviorService.INVENTORY_CAPACITY;
        Tile target = agent.targetLocation != null ? Tile.findNearest(agent.targetLocation) : null;

        if (agent.targetLocation == null || agent.currentPath.isEmpty()) {
            behaviorService.assignGoal(agent);
            replanPath(agent);
            return;
        }

        if (shouldDeliver && (target == null || !target.isCity())) {
            behaviorService.assignGoal(agent);
            replanPath(agent);
        } else if (!shouldDeliver && (target == null || target.isCity())) {
            behaviorService.assignGoal(agent);
            replanPath(agent);
        }
    }

    private void moveAlongPath(Agent agent) {
        if (agent.targetLocation == null) {
            return;
        }

        if (agent.currentPath.isEmpty()) {
            replanPath(agent);
        }

        if (agent.currentPath.isEmpty()) {
            return;
        }

        Tile nextTile = agent.currentPath.remove(0);
        agent.location = GeometryFactoryHolder.createPoint(
                nextTile.location.getX(),
                nextTile.location.getY());
    }

    private static boolean canHarvest(Agent agent, Tile tile) {
        return agent.job != null
                && !agent.isInventoryFull()
                && tile.hasResource()
                && tile.resourceType == agent.job.harvests();
    }

    private static void extractResources(Agent agent, Tile tile) {
        tile.quantity -= 1;
        agent.addToInventory(tile.resourceType, 1);

        if (tile.quantity <= 0) {
            tile.quantity = 0;
            tile.resourceType = null;
        }
        tile.persist();
    }

    private void replanPath(Agent agent) {
        if (agent.targetLocation == null) {
            return;
        }

        Tile start = Tile.findNearest(agent.location);
        Tile goal = Tile.findNearest(agent.targetLocation);
        if (start == null || goal == null) {
            return;
        }

        List<Tile> path = new ArrayList<>(pathfindingService.findPath(start, goal));
        if (!path.isEmpty() && path.get(0).id.equals(start.id)) {
            path.remove(0);
        }

        agent.currentPath.clear();
        agent.currentPath.addAll(path);
    }
}
