package uk.org.tfm.model.vehicle;

import java.util.List;
import uk.org.tfm.model.feed.FeedException;

/** Fetches current vehicle observations for the provider's configured coverage area. */
public interface VehiclePositionProvider {
  List<VehiclePosition> fetch() throws FeedException;
}
