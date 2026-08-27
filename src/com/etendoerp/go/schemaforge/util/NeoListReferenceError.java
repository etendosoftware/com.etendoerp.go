package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.model.domaintype.DomainType;
import org.openbravo.base.model.domaintype.EnumerateDomainType;

/**
 * Rewrites Openbravo's List-reference validation failure so it names the values the column
 * actually accepts.
 *
 * <p>The raw message is built by core's {@code BaseEnumerateDomainType.checkIsValidValue}, which
 * interpolates the allowed-value {@code Set} straight into the text:</p>
 *
 * <pre>
 *   Property Product.productType, value (123) is not allowed, it should be one of the
 *   following values: [I, S, E, R, O] but it is value 123
 * </pre>
 *
 * <p>That is already hard to read, and under the Redis cache module the set is a
 * {@code CachedSet} whose {@code toString()} is the default {@code Object} one, so the whole
 * useful half of the sentence degrades into an object identity:</p>
 *
 * <pre>
 *   ... one of the following values: com.etendoerp.redis.interfaces.CachedSet@2fa6db8 but it
 *   is value 123
 * </pre>
 *
 * <p>{@link NeoErrorSanitizer#redactObjectReferences} (ETP-4668) already stops that identity from
 * leaking, but redaction can only remove — it leaves the user told that their value was rejected
 * and never told which ones would have worked. This class closes the other half: it reads the
 * property named in the message, asks the DAL for its domain type, and renders the enumerate
 * values by iterating them rather than trusting whatever {@code toString()} the runtime happens
 * to supply. ETP-4996.</p>
 *
 * <p>Every failure path returns the message unchanged. An error message is a best-effort
 * courtesy: making it nicer must never turn a 400 the caller could act on into a 500.</p>
 */
public final class NeoListReferenceError {

  private static final Logger log = LogManager.getLogger();

  /**
   * Captures the entity, the property and the rejected value from core's wording.
   *
   * <p>Anchored on {@code Property <Entity>.<property>, value (<value>) is not allowed}, which is
   * {@code Property.toString()} ({@code entity + "." + name}) followed by core's literal text. The
   * repetition bounds are explicit rather than open-ended (SonarQube java:S5998 / S5852): the
   * message can carry caller-controlled data, and an unbounded run here would let a crafted value
   * drive catastrophic backtracking.</p>
   */
  private static final Pattern PROPERTY_PATTERN = Pattern.compile(
      "Property\\s+([A-Za-z_$][\\w$]{0,127})\\.([A-Za-z_$][\\w$]{0,127})\\s*,\\s*value\\s*\\(",
      Pattern.CASE_INSENSITIVE);

  /**
   * Captures the allowed-values clause, up to core's trailing {@code but it is value <x>}.
   *
   * <p>Reluctant and bounded so it stops at the first {@code but it is value}, and cannot run away
   * on a message that never contains one.</p>
   */
  private static final Pattern ALLOWED_VALUES_PATTERN = Pattern.compile(
      "(it should be one of the following values:\\s*)(.{0,2000}?)(\\s*but it is value\\b)",
      Pattern.CASE_INSENSITIVE);

  /**
   * Upper bound on how many values are spelled out. A List reference with more entries than this
   * is a catalog, not a choice a human is going to read off an error message, so the message says
   * how many were elided instead of dumping all of them.
   */
  static final int MAX_LISTED_VALUES = 40;

  private NeoListReferenceError() {
  }

  /**
   * Returns {@code message} with the allowed-values clause replaced by the column's real accepted
   * values, or {@code message} unchanged when it is not a List-reference failure or the values
   * cannot be resolved.
   *
   * @param message the (possibly already-translated) validation message; may be {@code null}
   * @return the enriched message, or the input unchanged
   */
  public static String enrich(String message) {
    return enrichWith(message, NeoListReferenceError::allowedValuesFor);
  }

  /**
   * Resolves {@code entityName.propertyName} to that column's accepted values.
   *
   * <p>Exists as a seam so the message-rewriting half can be tested without a running DAL model —
   * a plain unit test has no {@code ModelProvider}, so through {@link #enrich} alone every case
   * would collapse into the same "returned unchanged" outcome and the rewriting would go
   * uncovered.</p>
   */
  @FunctionalInterface
  interface AllowedValuesResolver {
    List<String> resolve(String entityName, String propertyName);
  }

  /** @see #enrich(String) */
  static String enrichWith(String message, AllowedValuesResolver resolver) {
    if (message == null || !ALLOWED_VALUES_PATTERN.matcher(message).find()) {
      return message;
    }
    Matcher property = PROPERTY_PATTERN.matcher(message);
    if (!property.find()) {
      return message;
    }
    List<String> allowed = resolver.resolve(property.group(1), property.group(2));
    if (allowed == null || allowed.isEmpty()) {
      return message;
    }
    Matcher clause = ALLOWED_VALUES_PATTERN.matcher(message);
    // The rendered values are quoted because `$` and `\` are replacement metacharacters; the
    // surrounding $1/$3 are real group references and must NOT be quoted.
    return clause.replaceFirst("$1" + Matcher.quoteReplacement(render(allowed)) + "$3");
  }

  /**
   * The accepted values of {@code entityName.propertyName}, or an empty list when the property is
   * unknown or is not backed by a List reference.
   */
  private static List<String> allowedValuesFor(String entityName, String propertyName) {
    try {
      // Both lookups pass `false` so an unknown entity/property returns null instead of throwing
      // CheckException — the message may name something this install does not have.
      Entity entity = ModelProvider.getInstance().getEntity(entityName, false);
      if (entity == null) {
        return List.of();
      }
      Property property = entity.getProperty(propertyName, false);
      if (property == null) {
        return List.of();
      }
      DomainType domainType = property.getDomainType();
      if (!(domainType instanceof EnumerateDomainType)) {
        return List.of();
      }
      Set<?> values = ((EnumerateDomainType) domainType).getEnumerateValues();
      if (values == null || values.isEmpty()) {
        return List.of();
      }
      // Iterated rather than rendered through the Set's own toString(): the whole point is that
      // the runtime's Set implementation (CachedSet) has no useful toString().
      List<String> rendered = new ArrayList<>();
      for (Object value : values) {
        if (value != null) {
          rendered.add(String.valueOf(value));
        }
      }
      // Sorted so the same column always reports the same order — an error message that reshuffles
      // between calls is needlessly hard to compare against a previous one.
      rendered.sort(String::compareTo);
      return rendered;
    } catch (RuntimeException e) {
      // A model lookup on an unknown/renamed entity throws. Enriching the message is never worth
      // failing the request over.
      log.debug("Could not resolve allowed values for {}.{}", entityName, propertyName, e);
      return List.of();
    }
  }

  /** Comma-separated list, truncated with a count when the reference carries too many values. */
  private static String render(List<String> values) {
    if (values.size() <= MAX_LISTED_VALUES) {
      return String.join(", ", values);
    }
    String head = values.subList(0, MAX_LISTED_VALUES).stream().collect(Collectors.joining(", "));
    return head + ", … (" + (values.size() - MAX_LISTED_VALUES) + " more)";
  }
}
