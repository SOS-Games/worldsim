package worldsim;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import worldsim.dto.AgentDto;
import worldsim.dto.BackendDebugDto;
import worldsim.dto.CityDto;
import worldsim.dto.DebugDto;
import worldsim.dto.MapDto;
import worldsim.dto.SqlDebugDto;
import worldsim.dto.TileDto;
import worldsim.dto.WorldStateDto;

import java.util.List;

@Path("/world")
public class WorldResource {

    @Inject
    TickMetrics tickMetrics;

    @Inject
    DebugService debugService;

    @Inject
    SqlDebugService sqlDebugService;

    @Inject
    BackendDebugService backendDebugService;

    /**
     * Static-ish map payload: dimensions plus non-grass tiles only (mountains, cities, biomes).
     * Load once on the client.
     */
    @GET
    @Path("/map")
    @Produces(MediaType.APPLICATION_JSON)
    public MapDto getMap() {
        List<TileDto> tiles = Tile.<Tile>list("terrainType <> ?1 or resourceType is not null", TerrainType.GRASS.code())
                .stream()
                .map(TileDto::from)
                .toList();
        return new MapDto(WorldConfig.MAP_WIDTH, WorldConfig.MAP_HEIGHT, tiles);
    }

    /**
     * Live agent snapshot. Paths are omitted by default for bandwidth; pass {@code paths=true} when
     * the UI path overlay is enabled.
     */
    @GET
    @Path("/state")
    @Produces(MediaType.APPLICATION_JSON)
    public WorldStateDto getState(@QueryParam("paths") @DefaultValue("false") boolean paths) {
        List<AgentDto> agents = Agent.all().stream()
                .map(agent -> AgentDto.from(agent, paths))
                .toList();
        List<CityDto> cities = City.all().stream().map(CityDto::from).toList();
        return new WorldStateDto(agents, tickMetrics.toDto(), cities);
    }

    /**
     * Machine-readable snapshot for CLI / agents. Prefer this over scraping the HUD.
     */
    @GET
    @Path("/debug")
    @Produces(MediaType.APPLICATION_JSON)
    public DebugDto getDebug() {
        return debugService.snapshot();
    }

    @GET
    @Path("/debug")
    @Produces(MediaType.TEXT_PLAIN)
    public String getDebugText() {
        return debugService.formatText(debugService.snapshot());
    }

    /**
     * Per-statement timings from the last tick plus Postgres table scan stats.
     */
    @GET
    @Path("/debug/sql")
    @Produces(MediaType.APPLICATION_JSON)
    public SqlDebugDto getSqlDebug() {
        return sqlDebugService.snapshot();
    }

    @GET
    @Path("/debug/sql")
    @Produces(MediaType.TEXT_PLAIN)
    public String getSqlDebugText() {
        return sqlDebugService.formatText(sqlDebugService.snapshot());
    }

    /**
     * JVM / tick health: heap, skipped ticks, last Java errors, time spent outside SQL.
     */
    @GET
    @Path("/debug/backend")
    @Produces(MediaType.APPLICATION_JSON)
    public BackendDebugDto getBackendDebug() {
        return backendDebugService.snapshot();
    }

    @GET
    @Path("/debug/backend")
    @Produces(MediaType.TEXT_PLAIN)
    public String getBackendDebugText() {
        return backendDebugService.formatText(backendDebugService.snapshot());
    }
}
