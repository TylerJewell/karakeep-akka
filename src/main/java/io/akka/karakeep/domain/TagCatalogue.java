package io.akka.karakeep.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One owner's tags, and how a name the model asked for becomes one of them. SPEC-001 R8-R10, R17.
 *
 * <p>Two indexes rather than one: a tag is matched by its normalised name, but two tags of one
 * owner may share a normalised name and may not share a display name, so the display name is what
 * decides whether a tag is created.
 */
public record TagCatalogue(Map<String, ResolvedTag> byDisplayName) {

  public static TagCatalogue empty() {
    return new TagCatalogue(Map.of());
  }

  /** What resolving a batch of names produced, so the caller can record only what is new. */
  public record Resolution(TagCatalogue catalogue, List<ResolvedTag> resolved, List<ResolvedTag> created) {}

  /**
   * @param idFor supplies an id for a tag that has to be created; called only then
   */
  public Resolution resolve(List<String> names, java.util.function.Function<String, String> idFor) {
    Map<String, ResolvedTag> display = new LinkedHashMap<>(byDisplayName);
    // R8 — an inferred name matches an existing tag by normalised name. Where several existing
    // tags share one normalised name the first inserted wins, which is the order they were
    // created in.
    Map<String, ResolvedTag> byNormalised = new LinkedHashMap<>();
    for (ResolvedTag tag : display.values()) {
      byNormalised.putIfAbsent(tag.normalisedName(), tag);
    }

    List<ResolvedTag> resolved = new ArrayList<>(names.size());
    List<ResolvedTag> created = new ArrayList<>();
    for (String name : names) {
      ResolvedTag existing = display.get(name);
      if (existing == null) {
        existing = byNormalised.get(TagName.normalise(name));
      }
      if (existing != null) {
        resolved.add(existing);
        continue;
      }
      // R9 and R10 — a name nothing matched is created under the name as given, even when it
      // normalises to something an inferred sibling also normalises to, and even when it is
      // empty. A tag created during this batch does not join the normalised index, so a sibling
      // that normalises alike is created rather than folded into it; it does join the display
      // index, so the same name twice in one batch is one tag.
      ResolvedTag fresh = new ResolvedTag(idFor.apply(name), name, TagName.normalise(name));
      display.put(name, fresh);
      resolved.add(fresh);
      created.add(fresh);
    }
    return new Resolution(new TagCatalogue(Map.copyOf(display)), resolved, created);
  }
}
