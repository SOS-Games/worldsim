package worldsim;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import worldsim.dto.AgentDto;
import worldsim.dto.TileDto;
import worldsim.dto.WorldStateDto;

import java.util.List;

@Path("/world")
public class WorldResource {

    @GET
    @Path("/state")
    @Produces(MediaType.APPLICATION_JSON)
    public WorldStateDto getState() {
        List<TileDto> tiles = Tile.all().stream().map(TileDto::from).toList();
        List<AgentDto> agents = Agent.all().stream().map(AgentDto::from).toList();
        return new WorldStateDto(tiles, agents);
    }
}
