package uk.org.tfm.model.catalog;

import java.util.List;

public record NearbyStopPage(List<NearbyStop> stops, boolean truncated) {}
