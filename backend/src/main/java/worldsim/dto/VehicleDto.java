package worldsim.dto;

import worldsim.Vehicle;

public record VehicleDto(long id, String type, CoordDto location, Long occupantId) {
    public static VehicleDto from(Vehicle vehicle) {
        return new VehicleDto(
                vehicle.id,
                vehicle.type != null ? vehicle.type.name() : null,
                CoordDto.from(vehicle.location),
                vehicle.occupantId);
    }
}
