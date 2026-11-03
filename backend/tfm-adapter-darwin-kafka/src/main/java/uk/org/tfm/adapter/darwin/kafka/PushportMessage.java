package uk.org.tfm.adapter.darwin.kafka;

import java.util.List;
import uk.org.tfm.model.rail.MovementSnapshot;
import uk.org.tfm.model.rail.PlatformUpdate;
import uk.org.tfm.model.rail.ScheduleRecord;

/**
 * The parsed contents of one Darwin push-port Kafka message: the platform updates (from {@code
 * TS}), the movement actuals/estimates (also from {@code TS}, one per timed location), and the
 * schedules (from {@code schedule}/SC) it carried. Any list may be empty. A single parse pass
 * produces all three so the consumer thread traverses each message once.
 */
public record PushportMessage(
    List<PlatformUpdate> platformUpdates,
    List<ScheduleRecord> schedules,
    List<MovementSnapshot> movements) {

  public static final PushportMessage EMPTY = new PushportMessage(List.of(), List.of(), List.of());
}
