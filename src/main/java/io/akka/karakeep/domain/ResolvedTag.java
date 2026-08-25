package io.akka.karakeep.domain;

/** A tag name the owner's catalogue has turned into an id. SPEC-001 R8, R9, R17. */
public record ResolvedTag(String tagId, String name, String normalisedName) {}
