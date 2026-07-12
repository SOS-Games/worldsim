package worldsim.dto;

import worldsim.Tile;

public record TileDto(long id, int x, int y, String terrainType, CoordDto location) {
    public static TileDto from(Tile tile) {
        return new TileDto(tile.id, tile.x, tile.y, tile.terrainType, CoordDto.from(tile.location));
    }
}
