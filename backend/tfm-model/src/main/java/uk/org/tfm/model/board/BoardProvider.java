package uk.org.tfm.model.board;

/** Supplies station boards without exposing a provider's HTTP, JSON, storage, or wire format. */
public interface BoardProvider {
  Board getDepartureBoard(String stationId, int rows);

  Board getArrivalBoard(String stationId, int rows);
}
