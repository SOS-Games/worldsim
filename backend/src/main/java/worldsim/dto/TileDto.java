package worldsim.dto;

import worldsim.Tile;

public record TileDto(
        long id,
        int x,
        int y,
        String terrainType,
        String infrastructureType,
        CoordDto location,
        String resourceType,
        int quantity,
        Long cityId) {
    public static TileDto from(Tile tile) {
        return new TileDto(
                tile.id,
                tile.x,
                tile.y,
                tile.terrainType,
                tile.infrastructureType,
                CoordDto.from(tile.location),
                tile.resourceType != null ? tile.resourceType.name() : null,
                tile.quantity,
                tile.cityId);
    }
}
