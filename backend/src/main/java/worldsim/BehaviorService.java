package worldsim;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BehaviorService {

    public static final int INVENTORY_CAPACITY = 10;

    /**
     * Full inventory → deliver to city. Otherwise → gather at nearest job resource.
     */
    public void assignGoal(Agent agent) {
        if (agent.job == null) {
            return;
        }

        if (agent.inventoryCount() >= INVENTORY_CAPACITY) {
            setTargetToNearestCity(agent);
        } else {
            setTargetToNearestResource(agent);
        }
    }

    public void setTargetToNearestCity(Agent agent) {
        Tile city = Tile.findNearestCity(agent.location);
        if (city != null) {
            agent.targetLocation = city.location;
            agent.currentPath.clear();
        }
    }

    public void setTargetToNearestResource(Agent agent) {
        Tile resource = Tile.findNearestResource(agent.job.harvests(), agent.location);
        if (resource != null) {
            agent.targetLocation = resource.location;
            agent.currentPath.clear();
        } else if (agent.inventoryCount() > 0) {
            // Nothing left to gather — deliver whatever is carried.
            setTargetToNearestCity(agent);
        } else {
            setTargetToNearestCity(agent);
        }
    }
}
