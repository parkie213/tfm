package uk.org.tfm.model.catalog;

import java.util.List;

/** A bounded set of stops; truncated means the caller should narrow its viewport. */
public record StopPage(List<Stop> stops, boolean truncated) {
  public StopPage {
    stops = List.copyOf(stops);
  }
}
