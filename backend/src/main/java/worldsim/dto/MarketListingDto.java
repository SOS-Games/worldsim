package worldsim.dto;

import worldsim.Market;

public record MarketListingDto(String resource, int stock, double price) {
    public static MarketListingDto from(Market listing) {
        return new MarketListingDto(
                listing.resourceType.name(),
                listing.stock,
                Math.round(listing.currentPrice() * 10.0) / 10.0);
    }
}
