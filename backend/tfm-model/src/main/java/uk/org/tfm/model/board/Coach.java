package uk.org.tfm.model.board;

import java.util.OptionalInt;

/** Operator-reported formation details; an absent loading value means unknown. */
public record Coach(String number, String coachClass, OptionalInt loading, String toilet) {}
