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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.utils.SIIUtils;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link NeoBackgroundDefaultsService}'s SII "defer the clave tipo to the Classic
 * DB trigger" pass (ETP-4784).
 *
 * <p>Documents built in the background (an invoice generated from a shipment, receipt or order)
 * never travel the HTTP create path, so no callout runs and the business partner's configured SII
 * key is never resolved. Classic covers exactly that with the {@code AEATSII_INVOICE_TRG} DB
 * trigger — but the trigger only fires when {@code EM_Aeatsii_Clave_Tipo IS NULL}, and the
 * declared-defaults pass had just stamped the generic {@code "F1"} into it. This suite pins the
 * exact conditions under which that column is restored to {@code null} — and, just as important,
 * the ones under which it must be left untouched.
 *
 * <p>{@code deferSiiKeyToPartnerTrigger}/{@code isOrganizationInSiiSystem} are private, so they
 * are exercised through the public
 * {@link NeoBackgroundDefaultsService#applyDeclaredDefaultsToBackgroundEntity} entry point, which
 * is the only caller. {@code NeoDefaultsService.resolveDefaults} is stubbed with a canned response
 * (its own resolution logic is covered by {@code NeoDefaultsServiceTest}) so these tests stay
 * focused on the deferral contract.
 */
public class NeoBackgroundDefaultsServiceTest {

  private static final String BG_SPEC_NAME = "sales-invoice";
  private static final String BG_ENTITY_NAME = "header";
  private static final String BG_PARENT_ID = "parent-doc-1";

  private static final String PROP_ORGANIZATION = "organization";
  private static final String PROP_SII_KEY = "aeatsiiClaveTipo";
  private static final String PROP_BUSINESS_PARTNER = "businessPartner";
  private static final String PROP_BP_SII_DEFAULT_ENABLED = "aeatsiiDefaultsiikey";
  private static final String PROP_BP_SII_KEY = "aeatsiiSiikeylist";

  /**
   * A declared default the entity does not expose as a property — it is skipped by
   * {@code applyDeclaredDefaultIfMissing}, but keeps the resolved defaults object non-empty so
   * the method reaches the SII deferral pass under test.
   */
  private static final String UNRELATED_DEFAULT = "etsgDateOperation";
  private static final String GENERIC_SII_KEY = "F1";

  // -------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------

  /**
   * Mutable fixture describing the document under test, so each test only has to flip the one
   * condition it is about.
   */
  private static final class Fixture {
    BaseOBObject entity = mock(BaseOBObject.class);
    Entity dalEntity = mock(Entity.class);
    BaseOBObject businessPartner = mock(BaseOBObject.class);
    Entity bpEntity = mock(Entity.class);
    Organization organization = mock(Organization.class);
    AEATSIIConfig siiConfig = mock(AEATSIIConfig.class);
  }

  /**
   * Wires the happy path: an invoice whose SII key was just filled in with the generic "F1", on an
   * organization registered in the SII, for a partner declaring its own SII key.
   */
  private static Fixture happyPathFixture() {
    Fixture f = new Fixture();
    when(f.entity.getEntity()).thenReturn(f.dalEntity);
    when(f.entity.getEntityName()).thenReturn("Invoice");

    when(f.dalEntity.getProperty(UNRELATED_DEFAULT)).thenReturn(null);
    when(f.dalEntity.hasProperty(PROP_SII_KEY)).thenReturn(true);
    when(f.dalEntity.hasProperty(PROP_BUSINESS_PARTNER)).thenReturn(true);
    when(f.dalEntity.hasProperty(PROP_ORGANIZATION)).thenReturn(true);

    // Generic AD default the declared-defaults pass had just stamped in.
    when(f.entity.get(PROP_SII_KEY)).thenReturn(GENERIC_SII_KEY);
    when(f.entity.get(PROP_ORGANIZATION)).thenReturn(f.organization);
    when(f.entity.get(PROP_BUSINESS_PARTNER)).thenReturn(f.businessPartner);

    when(f.businessPartner.getEntity()).thenReturn(f.bpEntity);
    when(f.bpEntity.hasProperty(PROP_BP_SII_DEFAULT_ENABLED)).thenReturn(true);
    when(f.bpEntity.hasProperty(PROP_BP_SII_KEY)).thenReturn(true);
    when(f.businessPartner.get(PROP_BP_SII_DEFAULT_ENABLED)).thenReturn(Boolean.TRUE);
    when(f.businessPartner.get(PROP_BP_SII_KEY)).thenReturn("F2");

    when(f.siiConfig.isAcogidaAlSII()).thenReturn(Boolean.TRUE);
    return f;
  }

  /**
   * The declared defaults the resolution pass normally returns for this document: the generic
   * "F1" must show up as a DECLARED default, because the deferral only ever undoes a value this
   * pass stamped, never one the caller provided.
   */
  private static JSONObject declaredDefaults(Object siiKey) throws JSONException {
    JSONObject defaults = new JSONObject().put(UNRELATED_DEFAULT, "2026-08-20");
    if (siiKey != null) {
      defaults.put(PROP_SII_KEY, siiKey);
    }
    return defaults;
  }

  /**
   * Runs {@code applyDeclaredDefaultsToBackgroundEntity} against {@code f} with every Etendo
   * static singleton it touches stubbed, so no live database is required.
   */
  private static void runBackgroundDefaults(Fixture f) throws JSONException {
    runBackgroundDefaults(f, declaredDefaults(GENERIC_SII_KEY));
  }

  /**
   * Same as {@link #runBackgroundDefaults(Fixture)} but with an explicit set of declared
   * defaults, so tests can vary what the pass claims to have stamped.
   */
  private static void runBackgroundDefaults(Fixture f, JSONObject defaults) throws JSONException {
    try (MockedStatic<NeoServletSupport> servletSupportMock = mockStatic(NeoServletSupport.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoDefaultsService> serviceMock = mockStatic(NeoDefaultsService.class);
         MockedStatic<SIIUtils> siiUtilsMock = mockStatic(SIIUtils.class)) {

      wireBackgroundEntityLookup(servletSupportMock, dalMock, obContextMock);
      siiUtilsMock.when(() -> SIIUtils.getSiiConfigFromOrg(any(Organization.class)))
          .thenReturn(f.siiConfig);

      serviceMock.when(() -> NeoDefaultsService.resolveDefaults(any(NeoContext.class),
          eq(BG_PARENT_ID))).thenReturn(new NeoResponse(200,
              new JSONObject().put("defaults", defaults)));

      NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          BG_SPEC_NAME, BG_ENTITY_NAME, f.entity, BG_PARENT_ID);
    }
  }

  /**
   * Wires {@code NeoServletSupport.findSpec} plus the OBDal/OBContext chain
   * {@code resolveBackgroundDefaults} needs to reach the deferral pass.
   */
  private static void wireBackgroundEntityLookup(MockedStatic<NeoServletSupport> servletSupportMock,
      MockedStatic<OBDal> dalMock, MockedStatic<OBContext> obContextMock) {
    SFSpec sfSpec = mock(SFSpec.class);
    when(sfSpec.getId()).thenReturn("spec-1");
    servletSupportMock.when(() -> NeoServletSupport.findSpec(BG_SPEC_NAME)).thenReturn(sfSpec);

    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("sf-entity-1");

    OBDal dal = mock(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
    @SuppressWarnings("unchecked")
    OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
    when(entityCriteria.list()).thenReturn(Collections.singletonList(sfEntity));

    obContextMock.when(OBContext::getOBContext).thenReturn(mock(OBContext.class));
  }

  private static void assertSiiKeyCleared(Fixture f) {
    verify(f.entity).set(eq(PROP_SII_KEY), isNull());
  }

  private static void assertSiiKeyUntouched(Fixture f) {
    verify(f.entity, never()).set(eq(PROP_SII_KEY), any());
  }

  // -------------------------------------------------------------------
  // Happy path
  // -------------------------------------------------------------------

  /**
   * The canonical case: partner declares its own SII key and the organization is registered in
   * the SII, so the generic "F1" is wiped and the column left {@code null} for
   * {@code AEATSII_INVOICE_TRG} to derive the partner-specific value on INSERT.
   */
  @Test
  public void testClearsSiiKeyWhenPartnerDeclaresItsOwnKeyAndOrgIsInSii() throws Exception {
    Fixture f = happyPathFixture();
    runBackgroundDefaults(f);
    assertSiiKeyCleared(f);
  }

  // -------------------------------------------------------------------
  // Partner-side guards
  // -------------------------------------------------------------------

  /**
   * A partner that does NOT enable the SII default keeps the generic key exactly as the declared
   * defaults produced it — there is nothing partner-specific for the trigger to derive.
   */
  @Test
  public void testKeepsSiiKeyWhenPartnerDefaultFlagIsNotEnabled() throws Exception {
    Fixture f = happyPathFixture();
    when(f.businessPartner.get(PROP_BP_SII_DEFAULT_ENABLED)).thenReturn(Boolean.FALSE);
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  /** The flag is on but no key is actually configured on the partner → nothing to defer. */
  @Test
  public void testKeepsSiiKeyWhenPartnerHasNoConfiguredKey() throws Exception {
    Fixture f = happyPathFixture();
    when(f.businessPartner.get(PROP_BP_SII_KEY)).thenReturn(null);
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  /** A blank (whitespace-only) partner key counts as "not configured". */
  @Test
  public void testKeepsSiiKeyWhenPartnerKeyIsBlank() throws Exception {
    Fixture f = happyPathFixture();
    when(f.businessPartner.get(PROP_BP_SII_KEY)).thenReturn("   ");
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  /** A partner entity without the SII columns (SII module not installed on it) is a no-op. */
  @Test
  public void testKeepsSiiKeyWhenPartnerEntityHasNoSiiProperties() throws Exception {
    Fixture f = happyPathFixture();
    when(f.bpEntity.hasProperty(PROP_BP_SII_DEFAULT_ENABLED)).thenReturn(false);
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  /**
   * The partner entity exposes the "use my SII default" flag but not the key column itself — a
   * partially-installed/older SII model. Both columns are required, so this is a no-op too.
   */
  @Test
  public void testKeepsSiiKeyWhenPartnerEntityHasNoSiiKeyProperty() throws Exception {
    Fixture f = happyPathFixture();
    when(f.bpEntity.hasProperty(PROP_BP_SII_KEY)).thenReturn(false);
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  /** No business partner set on the document → nothing partner-specific to derive. */
  @Test
  public void testKeepsSiiKeyWhenDocumentHasNoBusinessPartner() throws Exception {
    Fixture f = happyPathFixture();
    when(f.entity.get(PROP_BUSINESS_PARTNER)).thenReturn(null);
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  /**
   * The business-partner property carries something that is not a DAL bean (a raw id string left
   * by a caller that never resolved it). There is no partner record to read the key off, so the
   * column is left exactly as the declared defaults produced it.
   */
  @Test
  public void testKeepsSiiKeyWhenBusinessPartnerIsNotADalObject() throws Exception {
    Fixture f = happyPathFixture();
    when(f.entity.get(PROP_BUSINESS_PARTNER)).thenReturn("BP-001-RAW-ID");
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  // -------------------------------------------------------------------
  // Organization-side guard (isOrganizationInSiiSystem)
  // -------------------------------------------------------------------

  /**
   * For an organization NOT registered in the SII the key is meaningless, so the column is left
   * exactly as the declared defaults produced it.
   */
  @Test
  public void testKeepsSiiKeyWhenOrganizationIsNotInSiiSystem() throws Exception {
    Fixture f = happyPathFixture();
    when(f.siiConfig.isAcogidaAlSII()).thenReturn(Boolean.FALSE);
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  /** No AEATSII_CONFIG at all for the organization's legal entity → not in the SII. */
  @Test
  public void testKeepsSiiKeyWhenOrganizationHasNoSiiConfig() throws Exception {
    Fixture f = happyPathFixture();
    f.siiConfig = null;
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  /** An entity with no organization property cannot be checked against the SII → no-op. */
  @Test
  public void testKeepsSiiKeyWhenEntityHasNoOrganizationProperty() throws Exception {
    Fixture f = happyPathFixture();
    when(f.dalEntity.hasProperty(PROP_ORGANIZATION)).thenReturn(false);
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  /**
   * The organization property holds something that is not an {@link Organization} (e.g. an
   * unresolved id string), so the legal-entity lookup cannot run and the document is treated as
   * not registered in the SII.
   */
  @Test
  public void testKeepsSiiKeyWhenOrganizationIsNotAnOrganizationInstance() throws Exception {
    Fixture f = happyPathFixture();
    when(f.entity.get(PROP_ORGANIZATION)).thenReturn("ORG-001-RAW-ID");
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  // -------------------------------------------------------------------
  // Entity-side guards
  // -------------------------------------------------------------------

  /**
   * An entity without the SII column at all (module not installed) must be left completely
   * alone — this is the "everyone who does not use the SII" case.
   */
  @Test
  public void testKeepsSiiKeyWhenEntityHasNoSiiField() throws Exception {
    Fixture f = happyPathFixture();
    when(f.dalEntity.hasProperty(PROP_SII_KEY)).thenReturn(false);
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  /** An entity with no business-partner property is out of scope for the deferral. */
  @Test
  public void testKeepsSiiKeyWhenEntityHasNoBusinessPartnerProperty() throws Exception {
    Fixture f = happyPathFixture();
    when(f.dalEntity.hasProperty(PROP_BUSINESS_PARTNER)).thenReturn(false);
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  /**
   * If the column is already {@code null} the trigger will fire on its own — there is nothing to
   * undo, and the entity must not be written to again.
   */
  @Test
  public void testDoesNotWriteWhenSiiKeyIsAlreadyNull() throws Exception {
    Fixture f = happyPathFixture();
    when(f.entity.get(PROP_SII_KEY)).thenReturn(null);
    runBackgroundDefaults(f);
    assertSiiKeyUntouched(f);
  }

  // -------------------------------------------------------------------
  // "Only undo what THIS pass stamped" guard (wasStampedByDeclaredDefaults)
  // -------------------------------------------------------------------

  /**
   * The regression this guard exists for: a rectificative invoice whose caller set the SII key to
   * {@code "R"} before invoking the service. That value is NOT the declared default, so it was
   * never stamped by this pass and must survive untouched — clearing it would contradict the
   * invariant {@code applyDeclaredDefaultIfMissing} and the {@code protectedFields} of ETP-4783
   * uphold for this very column, and would silently downgrade the document's SII classification.
   */
  @Test
  public void testKeepsCallerProvidedSiiKeyThatDiffersFromTheDeclaredDefault() throws Exception {
    Fixture f = happyPathFixture();
    when(f.entity.get(PROP_SII_KEY)).thenReturn("R");

    runBackgroundDefaults(f, declaredDefaults(GENERIC_SII_KEY));

    assertSiiKeyUntouched(f);
  }

  /**
   * Nothing declared a default for the SII key, so whatever the column holds came from the
   * caller — there is no own stamp to undo.
   */
  @Test
  public void testKeepsSiiKeyWhenDefaultsDoNotDeclareIt() throws Exception {
    Fixture f = happyPathFixture();

    runBackgroundDefaults(f, declaredDefaults(null));

    assertSiiKeyUntouched(f);
  }

  /**
   * A declared default explicitly resolved to JSON {@code null} is not a stamp either: the pass
   * wrote nothing, so the current value belongs to the caller.
   */
  @Test
  public void testKeepsSiiKeyWhenDeclaredDefaultIsJsonNull() throws Exception {
    Fixture f = happyPathFixture();

    runBackgroundDefaults(f, declaredDefaults(JSONObject.NULL));

    assertSiiKeyUntouched(f);
  }

  /**
   * Pins the string-form comparison: the declared default travels as JSON (here a number) while
   * the entity holds the coerced property value (here the equivalent String). Comparing the two
   * with {@code equals()} would miss the match and leave the generic stamp in place, blocking
   * {@code AEATSII_INVOICE_TRG} — so they are deliberately compared via {@code toString()}.
   */
  @Test
  public void testClearsSiiKeyWhenDeclaredDefaultDiffersOnlyByJsonType() throws Exception {
    Fixture f = happyPathFixture();
    when(f.entity.get(PROP_SII_KEY)).thenReturn("1");

    runBackgroundDefaults(f, declaredDefaults(Integer.valueOf(1)));

    assertSiiKeyCleared(f);
  }

  /**
   * The flip side of the coercion pin: equal-looking types that are genuinely different values
   * are still treated as a caller-provided key.
   */
  @Test
  public void testKeepsSiiKeyWhenStringFormsDifferDespiteSameType() throws Exception {
    Fixture f = happyPathFixture();
    when(f.entity.get(PROP_SII_KEY)).thenReturn("F2");

    runBackgroundDefaults(f, declaredDefaults("F1"));

    assertSiiKeyUntouched(f);
  }

  // -------------------------------------------------------------------
  // Robustness
  // -------------------------------------------------------------------

  /**
   * This is an optional refinement: an internal failure (here, a DAL error while reading the
   * partner) must be swallowed and never abort the background document creation.
   */
  @Test
  public void testInternalFailureNeverAbortsDocumentCreation() throws Exception {
    Fixture f = happyPathFixture();
    when(f.entity.get(PROP_BUSINESS_PARTNER)).thenThrow(new RuntimeException("DB unavailable"));

    runBackgroundDefaults(f); // must not throw

    assertSiiKeyUntouched(f);
  }

  /**
   * A failure inside the SII config lookup is equally non-fatal.
   */
  @Test
  public void testSiiConfigLookupFailureNeverAbortsDocumentCreation() throws Exception {
    Fixture f = happyPathFixture();

    try (MockedStatic<NeoServletSupport> servletSupportMock = mockStatic(NeoServletSupport.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoDefaultsService> serviceMock = mockStatic(NeoDefaultsService.class);
         MockedStatic<SIIUtils> siiUtilsMock = mockStatic(SIIUtils.class)) {

      wireBackgroundEntityLookup(servletSupportMock, dalMock, obContextMock);
      siiUtilsMock.when(() -> SIIUtils.getSiiConfigFromOrg(any(Organization.class)))
          .thenThrow(new RuntimeException("SII config unavailable"));

      // The generic "F1" must show up as a DECLARED default: the deferral only undoes a value
      // this pass stamped, never one the caller provided.
      JSONObject defaults = new JSONObject()
          .put(UNRELATED_DEFAULT, "2026-08-20")
          .put(PROP_SII_KEY, GENERIC_SII_KEY);
      serviceMock.when(() -> NeoDefaultsService.resolveDefaults(any(NeoContext.class),
          eq(BG_PARENT_ID))).thenReturn(new NeoResponse(200,
              new JSONObject().put("defaults", defaults)));

      NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          BG_SPEC_NAME, BG_ENTITY_NAME, f.entity, BG_PARENT_ID);
    }

    assertSiiKeyUntouched(f);
  }

  /**
   * A {@code null} entity is a documented no-op: the method must return before it even resolves
   * the declared defaults, and no other document must be touched as a side effect.
   */
  @Test
  public void testNullEntityIsANoOp() {
    Fixture f = happyPathFixture();

    try (MockedStatic<NeoDefaultsService> serviceMock = mockStatic(NeoDefaultsService.class)) {
      NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          BG_SPEC_NAME, BG_ENTITY_NAME, null, BG_PARENT_ID);

      // The early return happens before any resolution work is scheduled.
      serviceMock.verify(
          () -> NeoDefaultsService.resolveDefaults(any(NeoContext.class), anyString()), never());
    }

    assertSiiKeyUntouched(f);
  }

  /** A blank spec name is the same documented no-op. */
  @Test
  public void testBlankSpecNameIsANoOp() {
    Fixture f = happyPathFixture();

    try (MockedStatic<NeoDefaultsService> serviceMock = mockStatic(NeoDefaultsService.class)) {
      NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          "  ", BG_ENTITY_NAME, f.entity, BG_PARENT_ID);

      serviceMock.verify(
          () -> NeoDefaultsService.resolveDefaults(any(NeoContext.class), anyString()), never());
    }

    assertSiiKeyUntouched(f);
  }

  /** A blank entity name likewise never reaches the deferral pass. */
  @Test
  public void testBlankEntityNameIsANoOp() {
    Fixture f = happyPathFixture();

    try (MockedStatic<NeoDefaultsService> serviceMock = mockStatic(NeoDefaultsService.class)) {
      NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          BG_SPEC_NAME, "  ", f.entity, BG_PARENT_ID);

      serviceMock.verify(
          () -> NeoDefaultsService.resolveDefaults(any(NeoContext.class), anyString()), never());
    }

    assertSiiKeyUntouched(f);
  }
}
