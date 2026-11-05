/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link BankBounceLineText}: how a statement-line description is normalized and when
 * it reads as the bounce of an earlier movement (the {@code return_text} signal), the end-to-end id
 * a PSD2 payment prints last, and the counterparty comparison. Pure functions, no DAL.
 *
 * <p>Ported from the first iteration's {@code ReturnLineTextTest} (commit {@code 1a5140cb9}) without
 * its {@code learnedForm} cases: the learned-text signal was dropped.
 *
 * @covers com.etendoerp.go.schemaforge.BankBounceLineText
 */
public class BankBounceLineTextTest {

  // ── normalize ──────────────────────────────────────────────────────────

  /** Normalize upper cases strips accents and collapses punctuation. */
  @Test
  public void normalizeUpperCasesStripsAccentsAndCollapsesPunctuation() {
    assertEquals("RETROCESION TRANSFERENCIA INMEDIATA",
        BankBounceLineText.normalize("  Retrocesión, transferencia -- inmediata. "));
  }

  /** Normalize transliterates german umlauts before stripping accents. */
  @Test
  public void normalizeTransliteratesGermanUmlautsBeforeStrippingAccents() {
    assertEquals("RUECKLASTSCHRIFT MUELLER STRASSE AERGER OEL",
        BankBounceLineText.normalize("Rücklastschrift Müller Straße Ärger Öl"));
  }

  /** Normalize reads blank and null as empty. */
  @Test
  public void normalizeReadsBlankAndNullAsEmpty() {
    assertEquals("", BankBounceLineText.normalize(null));
    assertEquals("", BankBounceLineText.normalize("   \t "));
  }

  // ── hasBounceWording ───────────────────────────────────────────────────

  /** The real Santander wording carries the accent; it must still hit the vocabulary. */
  @Test
  public void theSantanderReturnWordingWithItsAccentIsAReturn() {
    assertTrue(BankBounceLineText.hasBounceWording("RETROCESIÓN TRANSFERENCIA INMEDIATA"));
    assertTrue(BankBounceLineText.hasBounceWording("retrocesion transferencia inmediata"));
  }

  /** Every vocabulary word is recognized as a token. */
  @Test
  public void everyVocabularyWordIsRecognizedAsAToken() {
    for (String word : BankBounceLineText.BOUNCE_VOCABULARY) {
      assertTrue(word, BankBounceLineText.hasBounceWording("ABC " + word + " 123"));
    }
  }

  /** The german return is recognized from its umlaut spelling. */
  @Test
  public void theGermanReturnIsRecognizedFromItsUmlautSpelling() {
    assertTrue(BankBounceLineText.hasBounceWording("Rücklastschrift SEPA"));
  }

  /** Long words also match as a token prefix, so plural and inflected forms are returns. */
  @Test
  public void longVocabularyWordsMatchInflectedForms() {
    assertTrue(BankBounceLineText.hasBounceWording("DEVOLUCIONES RECIBO"));
    assertTrue(BankBounceLineText.hasBounceWording("Payment RETURNS"));
    assertTrue(BankBounceLineText.hasBounceWording("RECHAZOS SEPA"));
  }

  /** A short word ({@code DEVOL}) must be a whole token, or it would fire inside other words. */
  @Test
  public void shortVocabularyWordsOnlyMatchAWholeToken() {
    assertTrue(BankBounceLineText.hasBounceWording("DEVOL. RECIBO 123"));
    assertFalse(BankBounceLineText.hasBounceWording("DEVOLVER MATERIAL"));
  }

  /** A word that merely CONTAINS a vocabulary word (not as its prefix) is not a return. */
  @Test
  public void aVocabularyWordInsideAnotherTokenDoesNotCount() {
    assertFalse(BankBounceLineText.hasBounceWording("PRERETURN FEE"));
  }

  /** An ordinary transfer is not a return. */
  @Test
  public void anOrdinaryTransferIsNotAReturn() {
    assertFalse(BankBounceLineText.hasBounceWording(
        "TRANSFERENCIA INMEDIATA A FAVOR DE G. R. CONCEPTO 10000268 10000268-2"));
    assertFalse(BankBounceLineText.hasBounceWording(null));
    assertFalse(BankBounceLineText.hasBounceWording(""));
  }

  // ── lastToken ──────────────────────────────────────────────────────────

  /** Where a PSD2-initiated payment prints its end-to-end id ({@code <docNo>-<attempt>}). */
  @Test
  public void lastTokenIsTheEndToEndIdOfAPsd2Payment() {
    assertEquals("10000268-2", BankBounceLineText.lastToken(
        "TRANSFERENCIA INMEDIATA A FAVOR DE G. R. CONCEPTO 10000268 10000268-2"));
  }

  /** Last token upper cases and drops trailing punctuation. */
  @Test
  public void lastTokenUpperCasesAndDropsTrailingPunctuation() {
    assertEquals("ABC-1", BankBounceLineText.lastToken("concepto abc-1.)"));
    assertEquals("1000073-2", BankBounceLineText.lastToken("Factura 10000268. 1000073-2;"));
  }

  /** Last token of blank is empty. */
  @Test
  public void lastTokenOfBlankIsEmpty() {
    assertEquals("", BankBounceLineText.lastToken(null));
    assertEquals("", BankBounceLineText.lastToken("   "));
  }

  // ── sameCounterparty ───────────────────────────────────────────────────

  /** Counterparties compare once normalized. */
  @Test
  public void counterpartiesCompareOnceNormalized() {
    assertTrue(BankBounceLineText.sameCounterparty("I. Abedul", "i abedul"));
    assertTrue(BankBounceLineText.sameCounterparty("Müller GmbH", "MUELLER GMBH"));
    assertFalse(BankBounceLineText.sameCounterparty("I. A.", "G. R."));
  }

  /** Two empty names are NOT the same counterparty: the signal needs both lines to have one. */
  @Test
  public void anEmptyCounterpartyNeverMatches() {
    assertFalse(BankBounceLineText.sameCounterparty("", ""));
    assertFalse(BankBounceLineText.sameCounterparty(null, null));
    assertFalse(BankBounceLineText.sameCounterparty("", "G. R."));
  }
}
