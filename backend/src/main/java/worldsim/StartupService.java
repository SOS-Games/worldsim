package worldsim;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class StartupService {

    private static final Logger LOG = Logger.getLogger(StartupService.class);

    @Inject
    MapService mapService;

    @Inject
    WorldRuntime worldRuntime;

    void onStart(@Observes StartupEvent event) {
        long tiles = Tile.count();
        if (tiles != WorldConfig.EXPECTED_TILE_COUNT) {
            LOG.infof(
                    "World size mismatch (tiles=%d, expected=%d). Regenerating %dx%d map with %d agents.",
                    tiles,
                    WorldConfig.EXPECTED_TILE_COUNT,
                    WorldConfig.MAP_WIDTH,
                    WorldConfig.MAP_HEIGHT,
                    WorldConfig.AGENT_COUNT);
        }
        mapService.ensureWorldReady();
        worldRuntime.markReady();
        LOG.info("World is ready.");
    }
}
