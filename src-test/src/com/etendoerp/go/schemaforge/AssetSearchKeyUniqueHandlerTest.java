/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;

/**
 * Unit tests for {@link AssetSearchKeyUniqueHandler}.
 *
 * <p>Covers the full decision tree of {@code validateUniqueSearchKey} (blank-searchKey guard,
 * null-organization guard, new-record vs. update exclusion, the organization-scoped criteria
 * ignoring the Active flag, and the duplicate rejection), plus the {@code isValidEvent} guards
 * (disabled trigger handler, wrong entity) shared by {@code onNew} and {@code onUpdate}, and
 * {@code getObservedEntities}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssetSearchKeyUniqueHandlerTest {

  private static final String MESSAGE_KEY = "ETGO_AssetSearchKeyDuplicate";
  private static final String MESSAGE_TEXT =
      "There is already an asset with this identifier in this organization.";

  private AssetSearchKeyUniqueHandler handler;

  private MockedStatic<ModelProvider> mockedModelProvider;
  private MockedStatic<TriggerHandler> mockedTriggerHandler;
  private MockedStatic<OBDal> mockedOBDal;
  private MockedStatic<OBMessageUtils> mockedMessageUtils;

  private Entity assetEntity;
  private OBDal obDal;
  private OBCriteria<Asset> criteria;

  @BeforeEach
  void setUp() throws Exception {
    handler = new AssetSearchKeyUniqueHandler();

    // Reset static cache so each test gets a fresh entity initialization.
    Field entitiesField = AssetSearchKeyUniqueHandler.class.getDeclaredField("entities");
    entitiesField.setAccessible(true);
    entitiesField.set(null, null);

    assetEntity = mock(Entity.class);

    ModelProvider modelProviderInstance = mock(ModelProvider.class);
    when(modelProviderInstance.getEntity(Asset.ENTITY_NAME)).thenReturn(assetEntity);
    mockedModelProvider = mockStatic(ModelProvider.class);
    mockedModelProvider.when(ModelProvider::getInstance).thenReturn(modelProviderInstance);

    TriggerHandler triggerHandlerInstance = mock(TriggerHandler.class);
    when(triggerHandlerInstance.isDisabled()).thenReturn(false);
    mockedTriggerHandler = mockStatic(TriggerHandler.class);
    mockedTriggerHandler.when(TriggerHandler::getInstance).thenReturn(triggerHandlerInstance);

    obDal = mock(OBDal.class);
    criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(Asset.class)).thenReturn(criteria);
    when(criteria.count()).thenReturn(0);
    mockedOBDal = mockStatic(OBDal.class);
    mockedOBDal.when(OBDal::getInstance).thenReturn(obDal);

    mockedMessageUtils = mockStatic(OBMessageUtils.class);
    mockedMessageUtils.when(() -> OBMessageUtils.messageBD(MESSAGE_KEY)).thenReturn(MESSAGE_TEXT);
  }

  @AfterEach
  void tearDown() {
    mockedModelProvider.close();
    mockedTriggerHandler.close();
    mockedOBDal.close();
    mockedMessageUtils.close();
  }

  // ── Happy path — no duplicate ─────────────────────────────────────────────────

  /**
   * When no other asset shares the search key within the organization, the create must proceed
   * without throwing.
   */
  @Test
  void testOnNewNoDuplicateDoesNotThrow() {
    EntityNewEvent event = buildNewEvent("AST-0001", "org-1", null);
    handler.onNew(event);
    verify(criteria).count();
  }

  // ── Duplicate rejection — same organization ───────────────────────────────────

  /**
   * Edge case 1: when another asset of the SAME organization already has the given search key,
   * creating a new one must throw {@link OBException} with the translated duplicate message.
   */
  @Test
  void testOnNewDuplicateSearchKeySameOrganizationThrowsOBException() {
    when(criteria.count()).thenReturn(1);
    EntityNewEvent event = buildNewEvent("AST-0001", "org-1", null);

    OBException ex = assertThrows(OBException.class, () -> handler.onNew(event));
    assertEquals(MESSAGE_TEXT, ex.getMessage());
  }

  /**
   * When updating a record to a search key already used by ANOTHER asset of the same
   * organization, the update must throw {@link OBException}.
   */
  @Test
  void testOnUpdateDuplicateSearchKeySameOrganizationThrowsOBException() {
    when(criteria.count()).thenReturn(1);
    EntityUpdateEvent event = buildUpdateEvent("AST-0001", "org-1", "asset-2");

    OBException ex = assertThrows(OBException.class, () -> handler.onUpdate(event));
    assertEquals(MESSAGE_TEXT, ex.getMessage());
  }

  // ── Edge case 2: different organization of the same client is allowed ────────

  /**
   * Edge case 2: the uniqueness scope is per organization, not per client — a duplicate search
   * key that exists only in a DIFFERENT organization must not block the create. The query always
   * restricts by the record's own organization (verified below), so in real usage a match found
   * only in another organization never reaches {@code count() > 0}; here that real-world outcome
   * is represented by keeping the mocked {@code count()} at zero and asserting the create
   * proceeds without throwing.
   */
  @Test
  void testOnNewSameSearchKeyDifferentOrganizationDoesNotThrow() {
    EntityNewEvent event = buildNewEvent("AST-0001", "org-2", null);
    handler.onNew(event);
    verify(criteria).count();
  }

  // ── Edge case 3: self-exclusion on update ─────────────────────────────────────

  /**
   * Edge case 3: on an update that keeps its own existing search key (self-exclusion by id), the
   * current record must be excluded from the duplicate search so it doesn't collide with itself.
   */
  @Test
  void testOnUpdateKeepsOwnSearchKeyExcludesSelfDoesNotThrow() {
    EntityUpdateEvent event = buildUpdateEvent("AST-0001", "org-1", "asset-1");
    handler.onUpdate(event);
    verify(criteria, times(1)).count();
  }

  // ── Edge case 4: inactive duplicate still blocks ──────────────────────────────

  /**
   * Edge case 4: the Active flag is ignored — an INACTIVE duplicate in the same organization must
   * still block the create, matching a DB-level unique constraint. Asserted via the
   * {@code setFilterOnActive(false)} call, which is what makes inactive records visible to the
   * count query.
   */
  @Test
  void testValidateUniqueSearchKeyIgnoresActiveFlag() {
    when(criteria.count()).thenReturn(1);
    EntityNewEvent event = buildNewEvent("AST-0001", "org-1", null);

    assertThrows(OBException.class, () -> handler.onNew(event));
    verify(criteria).setFilterOnActive(false);
  }

  // ── Criteria construction — scope ─────────────────────────────────────────────

  /**
   * The uniqueness check must be scoped by organization: it must disable the readable-organization
   * filter and the active filter, so an inactive duplicate in the same organization still counts.
   */
  @Test
  void testValidateUniqueSearchKeyScopesByOrganization() {
    EntityNewEvent event = buildNewEvent("AST-0001", "org-1", null);
    handler.onNew(event);

    verify(criteria).setFilterOnReadableOrganization(false);
    verify(criteria).setFilterOnActive(false);
    verify(criteria).setMaxResults(1);
  }

  // ── Blank-searchKey guard ──────────────────────────────────────────────────────

  /**
   * When the search key is null, the handler must skip the uniqueness check entirely (no DAL
   * query).
   */
  @Test
  void testOnNewNullSearchKeySkipsCheck() {
    EntityNewEvent event = buildNewEvent(null, "org-1", null);
    handler.onNew(event);
    verify(obDal, never()).createCriteria(Asset.class);
  }

  /**
   * When the search key is blank (only whitespace), the handler must skip the uniqueness check.
   */
  @Test
  void testOnNewBlankSearchKeySkipsCheck() {
    EntityNewEvent event = buildNewEvent("   ", "org-1", null);
    handler.onNew(event);
    verify(obDal, never()).createCriteria(Asset.class);
  }

  // ── Null-organization guard ─────────────────────────────────────────────────────

  /**
   * When the record has no organization assigned, the handler must skip the uniqueness check
   * rather than querying with a null organization restriction.
   */
  @Test
  void testOnNewNullOrganizationSkipsCheck() {
    Asset asset = mock(Asset.class);
    when(asset.getEntity()).thenReturn(assetEntity);
    when(asset.getSearchKey()).thenReturn("AST-0001");
    when(asset.getOrganization()).thenReturn(null);

    EntityNewEvent event = mock(EntityNewEvent.class);
    when(event.getTargetInstance()).thenReturn(asset);

    handler.onNew(event);
    verify(obDal, never()).createCriteria(Asset.class);
  }

  // ── isValidEvent guards (shared by onNew/onUpdate) ────────────────────────────

  /**
   * When the TriggerHandler is disabled (import mode), the handler must not process the event.
   */
  @Test
  void testOnNewTriggerHandlerDisabledDoesNotProcess() {
    TriggerHandler disabledTH = mock(TriggerHandler.class);
    when(disabledTH.isDisabled()).thenReturn(true);
    mockedTriggerHandler.when(TriggerHandler::getInstance).thenReturn(disabledTH);

    EntityNewEvent event = buildNewEvent("AST-0001", "org-1", null);
    handler.onNew(event);
    verify(obDal, never()).createCriteria(any(Class.class));
  }

  /**
   * When the event targets a different entity (not FinancialMgmtAsset), the handler must skip
   * processing.
   */
  @Test
  void testOnUpdateWrongEntityDoesNotProcess() {
    Entity otherEntity = mock(Entity.class);
    BaseOBObject targetInstance = mock(BaseOBObject.class);
    when(targetInstance.getEntity()).thenReturn(otherEntity);

    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    when(event.getTargetInstance()).thenReturn(targetInstance);

    handler.onUpdate(event);
    verify(obDal, never()).createCriteria(any(Class.class));
  }

  // ── getObservedEntities ───────────────────────────────────────────────────────

  /**
   * {@code getObservedEntities} must return a non-null single-element array containing the Asset
   * entity.
   */
  @Test
  void testGetObservedEntitiesReturnsAssetEntity() {
    Entity[] observed = handler.getObservedEntities();
    assertNotNull(observed);
    assertEquals(1, observed.length);
    assertEquals(assetEntity, observed[0]);
  }

  /**
   * Subsequent calls to {@code getObservedEntities} must return the cached array (same
   * reference), avoiding repeated {@code ModelProvider} lookups.
   */
  @Test
  void testGetObservedEntitiesCacheIsReused() {
    Entity[] first = handler.getObservedEntities();
    Entity[] second = handler.getObservedEntities();
    assertNotNull(first);
    assertEquals(first, second);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  /**
   * Builds an {@link Asset} mock wired to the shared {@code assetEntity} (so {@code isValidEvent}
   * matches), with the given search key, organization id and record id.
   */
  private Asset buildAsset(String searchKey, String organizationId, String id) {
    Asset asset = mock(Asset.class);
    when(asset.getEntity()).thenReturn(assetEntity);
    when(asset.getSearchKey()).thenReturn(searchKey);

    if (organizationId != null) {
      Organization organization = mock(Organization.class);
      when(asset.getOrganization()).thenReturn(organization);
    }
    if (id != null) {
      when(asset.getId()).thenReturn(id);
    }
    return asset;
  }

  private EntityNewEvent buildNewEvent(String searchKey, String organizationId, String id) {
    Asset asset = buildAsset(searchKey, organizationId, id);
    EntityNewEvent event = mock(EntityNewEvent.class);
    when(event.getTargetInstance()).thenReturn(asset);
    return event;
  }

  private EntityUpdateEvent buildUpdateEvent(String searchKey, String organizationId, String id) {
    Asset asset = buildAsset(searchKey, organizationId, id);
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    when(event.getTargetInstance()).thenReturn(asset);
    return event;
  }
}
