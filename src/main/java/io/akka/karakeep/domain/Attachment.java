package io.akka.karakeep.domain;

/**
 * One tag on one bookmark, and who put it there.
 *
 * @param tagId the catalogue id, unique per user
 * @param name the tag's display name, carried here so a reader of a bookmark does not have to
 *     resolve it against the catalogue
 */
public record Attachment(String tagId, String name, AttachedBy attachedBy) {}
