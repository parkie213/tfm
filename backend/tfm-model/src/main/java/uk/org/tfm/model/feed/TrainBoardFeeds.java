package uk.org.tfm.model.feed;

/** Health of the two on-demand train-board products, which are separate subscriptions. */
public record TrainBoardFeeds(FeedHealth departures, FeedHealth arrivals) {}
