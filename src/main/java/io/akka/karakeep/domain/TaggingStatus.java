package io.akka.karakeep.domain;

/** What a bookmark reports about its own tagging. SPEC-001 §2, R19, R20. */
public enum TaggingStatus {
  PENDING,
  SUCCESS,
  FAILURE
}
