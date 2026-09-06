package worldsim;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.List;

@Entity
@Table(
        name = "market",
        uniqueConstraints = @UniqueConstraint(columnNames = {"city_id", "resource_type"}))
public class Market extends PanacheEntity {
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "city_id")
    public City city;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type")
    public ResourceType resourceType;

    public int stock;

    @Column(name = "base_price")
    public int basePrice;

    @Column(name = "target_stock")
    public int targetStock;

    public double currentPrice() {
        return price(basePrice, targetStock, stock);
    }

    public static double price(int basePrice, int targetStock, int stock) {
        return basePrice * ((double) targetStock / (stock + 1));
    }

    /** SQL expression for {@link #currentPrice()} on a market table alias. */
    public static String priceSql(String alias) {
        return "((%s.base_price::double precision * %s.target_stock) / (%s.stock + 1.0))"
                .formatted(alias, alias, alias);
    }

    public static void seedFor(City city) {
        for (ResourceType type : ResourceType.values()) {
            Market listing = new Market();
            listing.city = city;
            listing.resourceType = type;
            listing.stock = 0;
            listing.basePrice = type.basePrice();
            listing.targetStock = WorldConfig.MARKET_TARGET_STOCK;
            listing.persist();
        }
    }

    public static List<Market> all() {
        return listAll();
    }
}
