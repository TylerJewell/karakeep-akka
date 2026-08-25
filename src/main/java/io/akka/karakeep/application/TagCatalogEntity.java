package io.akka.karakeep.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.karakeep.domain.ResolvedTag;
import io.akka.karakeep.domain.TagCatalogue;
import io.akka.karakeep.domain.TagName;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One owner's tags. Entity id is the owner id, which is what keeps tags from being shared.
 * SPEC-001 R8–R10, R17.
 */
@Component(id = "tag-catalog")
public class TagCatalogEntity extends EventSourcedEntity<TagCatalogEntity.Tags, TagCatalogEntity.Event> {

  public record Tags(List<ResolvedTag> tags) {
    TagCatalogue catalogue() {
      Map<String, ResolvedTag> byName = new LinkedHashMap<>();
      for (ResolvedTag tag : tags) {
        byName.put(tag.name(), tag);
      }
      return new TagCatalogue(byName);
    }
  }

  public sealed interface Event {}

  @TypeName("tags-created")
  public record TagsCreated(List<ResolvedTag> created) implements Event {}

  /** The names to resolve, and the resolved tags in the same order. */
  public record Resolved(List<ResolvedTag> tags) {}

  private final String ownerId;

  public TagCatalogEntity(EventSourcedEntityContext context) {
    this.ownerId = context.entityId();
  }

  @Override
  public Tags emptyState() {
    return new Tags(List.of());
  }

  public Effect<Resolved> resolve(List<String> names) {
    // The counter runs across the whole batch, not just the stored tags: R9 has one batch
    // creating several tags that normalise alike, and an id derived from the stored count alone
    // would be the same id for all of them.
    int[] suffix = {currentState().tags().size()};
    var resolution =
        currentState()
            .catalogue()
            .resolve(
                names, name -> ownerId + ":" + TagName.normalise(name) + ":" + suffix[0]++);
    if (resolution.created().isEmpty()) {
      return effects().reply(new Resolved(resolution.resolved()));
    }
    return effects()
        .persist(new TagsCreated(resolution.created()))
        .thenReply(unused -> new Resolved(resolution.resolved()));
  }

  public ReadOnlyEffect<Tags> all() {
    return effects().reply(currentState());
  }

  @Override
  public Tags applyEvent(Event event) {
    return switch (event) {
      case TagsCreated e -> {
        List<ResolvedTag> next = new ArrayList<>(currentState().tags());
        next.addAll(e.created());
        yield new Tags(List.copyOf(next));
      }
    };
  }
}
