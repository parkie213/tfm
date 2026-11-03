package uk.org.tfm.model.rail;

import java.util.List;

/** A reported train schedule and its ordered public calling points. */
public record ScheduleRecord(TrainSchedule header, List<TrainCallingPoint> callingPoints) {}
