package worldsim.dto;

import org.locationtech.jts.geom.Point;

public record CoordDto(double x, double y) {
    public static CoordDto from(Point point) {
        return new CoordDto(point.getX(), point.getY());
    }
}
