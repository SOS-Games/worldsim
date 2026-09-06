package worldsim.dto;

import worldsim.City;
import worldsim.Market;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record CityDto(
        long id, String name, int x, int y, boolean harbor, int boatStock, List<MarketListingDto> listings) {
    public static CityDto from(City city) {
        List<Market> source = city.listings;
        if (source == null || source.isEmpty()) {
            source = Market.list("city", city);
        }
        List<MarketListingDto> listings = new ArrayList<>();
        for (Market listing : source) {
            listings.add(MarketListingDto.from(listing));
        }
        listings.sort(Comparator.comparing(MarketListingDto::resource));
        return new CityDto(city.id, city.name, city.x, city.y, city.harbor, city.boatStock, listings);
    }
}
