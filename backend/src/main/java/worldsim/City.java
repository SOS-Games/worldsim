package worldsim;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "city")
public class City extends PanacheEntity {
    public String name;
    public int x;
    public int y;
    public boolean harbor;

    @Column(name = "boat_stock")
    public int boatStock;

    @Column(name = "wagon_stock")
    public int wagonStock;

    @OneToMany(mappedBy = "city", fetch = FetchType.EAGER)
    public List<Market> listings = new ArrayList<>();

    public static City atGrid(int x, int y) {
        return find("x = ?1 and y = ?2", x, y).firstResult();
    }

    public static List<City> all() {
        return listAll();
    }
}
