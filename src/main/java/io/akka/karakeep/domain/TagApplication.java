package io.akka.karakeep.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the bookmark's tags become once the model has answered. SPEC-001 R11–R14.
 *
 * <p>The whole rule is here rather than in the entity because it is a decision about two lists,
 * and its awkward case — an empty answer meaning "no change" rather than "no tags" — is the one
 * worth being able to run on its own.
 */
public final class TagApplication {

  private TagApplication() {}

  /**
   * @param existing what the bookmark carries now
   * @param inferred the tags the model asked for, already resolved against the owner's catalogue
   * @return the bookmark's attachments afterwards
   */
  public static List<Attachment> apply(
      List<Attachment> existing, List<ResolvedTag> inferred) {
    // R12 — an empty answer changes nothing, and in particular does not detach what a previous
    // run attached.
    if (inferred.isEmpty()) {
      return List.copyOf(existing);
    }

    List<Attachment> kept = new ArrayList<>();
    Set<String> keptIds = new LinkedHashSet<>();
    for (Attachment attachment : existing) {
      // R11 — the previous run's tags go; a tag the owner attached stays.
      if (attachment.attachedBy() == AttachedBy.HUMAN) {
        kept.add(attachment);
        keptIds.add(attachment.tagId());
      }
    }

    for (ResolvedTag tag : inferred) {
      // R13 and R14 — a bookmark carries a tag once, whoever attached it, so a tag already held
      // by a human attachment is not attached again as the model's.
      if (keptIds.add(tag.tagId())) {
        kept.add(new Attachment(tag.tagId(), tag.name(), AttachedBy.AI));
      }
    }
    return List.copyOf(kept);
  }
}
