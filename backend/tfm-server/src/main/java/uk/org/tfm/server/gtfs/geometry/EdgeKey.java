package uk.org.tfm.server.gtfs.geometry;

/** An undirected OSM edge keyed by its node pair (canonicalised so {@code a < b}). */
public record EdgeKey(long a, long b) {
  public static EdgeKey of(long x, long y) {
    return x < y ? new EdgeKey(x, y) : new EdgeKey(y, x);
  }
}
