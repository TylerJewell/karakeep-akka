package io.akka.karakeep.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

/**
 * One owner's tagging preference. SPEC-001 R1.
 *
 * <p>Key-value rather than event-sourced: nothing in the slice asks how the preference got to be
 * what it is, only what it is now.
 */
@Component(id = "user")
public class UserEntity extends KeyValueEntity<UserEntity.Settings> {

  public record Settings(boolean autoTaggingEnabled) {}

  @Override
  public Settings emptyState() {
    // A user nobody has configured tags. This is the source's default too: the check there is
    // `autoTaggingEnabled === false`, so an absent preference does not stop tagging.
    return new Settings(true);
  }

  public Effect<Done> setAutoTagging(boolean enabled) {
    return effects().updateState(new Settings(enabled)).thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<Settings> settings() {
    return effects().reply(currentState());
  }
}
