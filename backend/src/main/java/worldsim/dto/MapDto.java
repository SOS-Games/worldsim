package worldsim.dto;

import java.util.List;

public record MapDto(int width, int height, List<TileDto> tiles) {}
