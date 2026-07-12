package worldsim;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class StartupService {

    @Inject
    MapService mapService;

    void onStart(@Observes StartupEvent event) {
        if (Tile.count() == 0) {
            mapService.generateGrid(20, 20);
        } else {
            mapService.ensureMapFeatures();
        }
    }
}
