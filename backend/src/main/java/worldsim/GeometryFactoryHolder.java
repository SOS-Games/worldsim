package worldsim;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

public final class GeometryFactoryHolder {
    private static final int SRID = 4326;
    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(), SRID);

    private GeometryFactoryHolder() {}

    public static Point createPoint(double x, double y) {
        Point point = FACTORY.createPoint(new Coordinate(x, y));
        point.setSRID(SRID);
        return point;
    }
}
