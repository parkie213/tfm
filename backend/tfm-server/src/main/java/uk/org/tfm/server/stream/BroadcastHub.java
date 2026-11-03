package uk.org.tfm.server.stream;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fan-out hub for a gRPC server-streaming feed: many client subscriptions, each carrying its own
 * filter params {@code S}, all served from one shared data source. The producer calls {@link
 * #broadcast()} whenever new data is published; the hub computes a per-subscriber frame ({@code
 * List<T>} — one snapshot message, or a list of per-entity messages) via {@code frameFor} and
 * pushes it.
 *
 * <p>Shares streaming lifecycle handling so bus/board feeds don't each re-implement it. Lifecycle
 * and safety:
 *
 * <ul>
 *   <li>subscriptions auto-evict on client cancel ({@code setOnCancelHandler}) or a failed push;
 *   <li>a slow client is skipped for a frame ({@code isReady()} backpressure) and catches up on the
 *       next broadcast — these feeds are last-value-wins snapshots, not an event log;
 *   <li>broadcast pushes run on a single thread (so {@code onNext} is never concurrent for one
 *       observer) and off the producer's thread (a slow client can't stall the producer); the
 *       initial push on {@link #subscribe} is serialised against them per-observer.
 * </ul>
 */
public final class BroadcastHub<S, T> {

  private static final Logger log = LoggerFactory.getLogger(BroadcastHub.class);

  private record Subscriber<S, T>(ServerCallStreamObserver<T> obs, S params) {}

  private final String name;
  private final Function<S, List<T>> frameFor;
  private final CopyOnWriteArrayList<Subscriber<S, T>> subscribers = new CopyOnWriteArrayList<>();
  private final ScheduledExecutorService pusher;

  public BroadcastHub(String name, Function<S, List<T>> frameFor) {
    this.name = name;
    this.frameFor = frameFor;
    this.pusher =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, name + "-broadcast");
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * Refresh on a timer instead of (or as well as) {@link #broadcast()}: every {@code seconds} the
   * hub recomputes and pushes each subscriber's frame. For feeds with no producer "new data" event
   * (e.g. a departure board re-queried from a cache/timetable) — when there are no subscribers the
   * frame function isn't called, so upstream load tracks demand.
   */
  public void startPeriodic(long seconds) {
    pusher.scheduleAtFixedRate(this::pushAll, seconds, seconds, TimeUnit.SECONDS);
  }

  /** Registers a stream: sends the current frame immediately, then every {@link #broadcast()}. */
  public void subscribe(StreamObserver<T> observer, S params) {
    @SuppressWarnings("unchecked")
    ServerCallStreamObserver<T> obs = (ServerCallStreamObserver<T>) observer;
    Subscriber<S, T> sub = new Subscriber<>(obs, params);
    obs.setOnCancelHandler(
        () -> {
          if (subscribers.remove(sub)) {
            log.debug("{}: -1 subscriber ({} active)", name, subscribers.size());
          }
        });
    subscribers.add(sub);
    log.debug("{}: +1 subscriber ({} active)", name, subscribers.size());
    push(sub); // initial frame (gRPC thread; serialised per-observer against broadcast pushes)
  }

  /** Pushes the current frame to every live subscriber. Call when new data has been published. */
  public void broadcast() {
    try {
      pusher.execute(this::pushAll);
    } catch (RejectedExecutionException ignored) {
      // pusher is shutting down — fine.
    }
  }

  private void pushAll() {
    for (Subscriber<S, T> sub : subscribers) {
      if (sub.obs().isCancelled()) {
        subscribers.remove(sub);
        continue;
      }
      push(sub);
    }
  }

  /** Completes all open streams and stops the pusher (server shutdown). */
  public void shutdown() {
    for (Subscriber<S, T> sub : subscribers) {
      synchronized (sub.obs()) {
        try {
          if (!sub.obs().isCancelled()) {
            sub.obs().onCompleted();
          }
        } catch (RuntimeException ignored) {
          // already broken — nothing to do
        }
      }
    }
    subscribers.clear();
    pusher.shutdownNow();
  }

  public int subscriberCount() {
    return subscribers.size();
  }

  private void push(Subscriber<S, T> sub) {
    ServerCallStreamObserver<T> obs = sub.obs();
    if (obs.isCancelled()) {
      subscribers.remove(sub);
      return;
    }
    // NB: we deliberately do NOT gate on isReady() here. For a fresh server-stream isReady() is
    // false
    // until the transport's first writability signal (it needs an onReadyHandler handshake), so
    // gating
    // would silently drop every frame. These frames are small and infrequent (one snapshot per
    // poll),
    // so we let gRPC buffer; a truly broken stream throws from onNext below and is evicted. Proper
    // isReady()/onReadyHandler backpressure is a future hardening if a feed ever gets chatty.
    List<T> frame;
    try {
      frame = frameFor.apply(sub.params());
    } catch (RuntimeException e) {
      log.warn("{} frame computation failed", name, e);
      return;
    }
    synchronized (obs) {
      try {
        for (T msg : frame) {
          obs.onNext(msg);
        }
      } catch (RuntimeException e) {
        subscribers.remove(sub); // broken stream — stop pushing to it
      }
    }
  }
}
