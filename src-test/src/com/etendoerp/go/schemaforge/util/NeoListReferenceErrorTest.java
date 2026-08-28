package com.etendoerp.go.schemaforge.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.Test;

import com.etendoerp.go.schemaforge.util.NeoListReferenceError.AllowedValuesResolver;

/**
 * Unit tests for {@link NeoListReferenceError} — ETP-4996.
 *
 * <p>Exercised through the {@code enrichWith} seam so the message rewriting is covered without a
 * running DAL model: a plain unit test has no {@code ModelProvider}, so through the public
 * {@link NeoListReferenceError#enrich} alone every case would collapse into the same "returned
 * unchanged" outcome and the interesting half would go untested.</p>
 */
public class NeoListReferenceErrorTest {

  /** The live shape of the leak, as measured in the field: the Set renders as an object identity. */
  private static final String LEAKED = "Property Product.productType, value (123) is not allowed, "
      + "it should be one of the following values: com.etendoerp.redis.interfaces.CachedSet@2fa6db8 "
      + "but it is value 123";

  private static final AllowedValuesResolver PRODUCT_TYPES =
      (entity, property) -> List.of("E", "I", "O", "R", "S");

  private static final AllowedValuesResolver NOTHING = (entity, property) -> List.of();

  // ── the fix: the message ends up naming what the column accepts ──────────────

  @Test
  public void enrichWith_replacesTheLeakedObjectIdentityWithTheRealValues() {
    assertEquals(
        "Property Product.productType, value (123) is not allowed, "
            + "it should be one of the following values: E, I, O, R, S but it is value 123",
        NeoListReferenceError.enrichWith(LEAKED, PRODUCT_TYPES));
  }

  @Test
  public void enrichWith_alsoRewritesAReadableButUglySetDump() {
    // When the Set does render, the values are technically present — as a Java collection dump.
    // Normalizing it means the caller sees one wording regardless of which Set implementation
    // happened to be in play.
    String message = "Property Product.productType, value (9) is not allowed, "
        + "it should be one of the following values: [I, S, E, R, O] but it is value 9";
    assertTrue(NeoListReferenceError.enrichWith(message, PRODUCT_TYPES)
        .contains("following values: E, I, O, R, S but it is value 9"));
  }

  @Test
  public void enrichWith_passesTheEntityAndPropertyItParsedToTheResolver() {
    String[] seen = new String[2];
    NeoListReferenceError.enrichWith(LEAKED, (entity, property) -> {
      seen[0] = entity;
      seen[1] = property;
      return List.of("I");
    });
    assertEquals("Product", seen[0]);
    assertEquals("productType", seen[1]);
  }

  @Test
  public void enrichWith_insertsValuesContainingReplacementMetacharactersLiterally() {
    // `$` and `\` are special to Matcher.appendReplacement; an unquoted value would either
    // corrupt the message or throw.
    String result = NeoListReferenceError.enrichWith(LEAKED, (e, p) -> List.of("A$1", "B\\C"));
    assertTrue(result, result.contains("following values: A$1, B\\C but it is value 123"));
  }

  @Test
  public void enrichWith_truncatesAnOversizedCatalogAndSaysHowManyWereElided() {
    List<String> many = IntStream.range(0, NeoListReferenceError.MAX_LISTED_VALUES + 7)
        .mapToObj(i -> String.format("V%03d", i))
        .collect(Collectors.toList());
    String result = NeoListReferenceError.enrichWith(LEAKED, (e, p) -> many);
    assertTrue(result, result.contains("… (7 more)"));
  }

  // ── the contract: anything it cannot improve comes back untouched ────────────

  @Test
  public void enrich_null_returnsNull() {
    assertNull(NeoListReferenceError.enrich(null));
  }

  @Test
  public void enrichWith_unresolvableProperty_returnsMessageUnchanged() {
    // The fallback that matters most: a "helpful" error message is never worth corrupting the
    // message it was trying to improve, nor failing the request over.
    assertEquals(LEAKED, NeoListReferenceError.enrichWith(LEAKED, NOTHING));
  }

  @Test
  public void enrichWith_resolverReturningNull_returnsMessageUnchanged() {
    assertEquals(LEAKED, NeoListReferenceError.enrichWith(LEAKED, (e, p) -> null));
  }

  @Test
  public void enrichWith_unrelatedMessages_areUntouched() {
    String duplicate = "A record with this value already exists. This value must be unique.";
    String notNull = "null value in column \"c_bpartner_location_id\" of relation \"c_invoice\" "
        + "violates not-null constraint";
    assertEquals(duplicate, NeoListReferenceError.enrichWith(duplicate, PRODUCT_TYPES));
    assertEquals(notNull, NeoListReferenceError.enrichWith(notNull, PRODUCT_TYPES));
  }

  @Test
  public void enrichWith_allowedValuesClauseWithoutPropertyPrefix_isUntouched() {
    String message = "it should be one of the following values: [I, S] but it is value 9";
    assertEquals(message, NeoListReferenceError.enrichWith(message, PRODUCT_TYPES));
  }

  @Test
  public void enrichWith_propertyPrefixWithoutAllowedValuesClause_isUntouched() {
    String message = "Property Product.productType, value (123) is not allowed.";
    assertEquals(message, NeoListReferenceError.enrichWith(message, PRODUCT_TYPES));
  }

  /**
   * A message with no {@code but it is value} tail must be returned untouched, and cheaply.
   * Guards the literal-marker scan that replaced the reluctant-wildcard pattern (SonarQube
   * java:S5852): the message can carry caller-controlled data, so the clause is located by two
   * linear {@code indexOf} calls that cannot backtrack.
   */
  @Test
  public void enrichWith_unterminatedClause_doesNotHang() {
    String message = "Property Product.productType, value (1) is not allowed, "
        + "it should be one of the following values: " + "x".repeat(5000);
    long start = System.nanoTime();
    String result = NeoListReferenceError.enrichWith(message, PRODUCT_TYPES);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    assertEquals(message, result);
    assertTrue("enrich took " + elapsedMs + "ms on an unterminated clause", elapsedMs < 1000);
  }

  /**
   * The tail marker only closes the clause on a word boundary — the {@code \b} the previous
   * pattern carried. A longer word starting with it is not the tail, so the scan moves on.
   */
  @Test
  public void enrichWith_tailMarkerInsideALongerWord_isNotTheEndOfTheClause() {
    String message = "Property Product.productType, value (1) is not allowed, "
        + "it should be one of the following values: [I, S] but it is valueless "
        + "but it is value 1";
    assertTrue(NeoListReferenceError.enrichWith(message, PRODUCT_TYPES)
        .contains("following values: E, I, O, R, S but it is value 1"));
  }
}
