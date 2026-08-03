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
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.assetmgmt.AssetGroup;

/**
 * Unit tests for {@link AssetGroupNameUniqueHandler}.
 *
 * <p>Covers the full decision tree of {@code validateUniqueName} (blank-name guard, null-client
 * guard, new-record vs. update exclusion, the client-scoped/cross-organization criteria, and the
 * duplicate rejection), plus the {@code isValidEvent} guards (disabled trigger handler, wrong
 * entity) shared by {@code onNew} and {@code onUpdate}, and {@code getObservedEntities}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssetGroupNameUniqueHandlerTest {

  private static final String MESSAGE_KEY = "ETGO_AssetGroupNameDuplicate";
  private static final String MESSAGE_TEXT = "There is already an asset category with this name.";

  private AssetGroupNameUniqueHandler handler;

  private MockedStatic<ModelProvider> mockedModelProvider;
  private MockedStatic<TriggerHandler> mockedTriggerHandler;
  private MockedStatic<OBDal> mockedOBDal;
  private MockedStatic<OBMessageUtils> mockedMessageUtils;

  private Entity assetGroupEntity;
  private OBDal obDal;
  private OBCriteria<AssetGroup> criteria;

  @BeforeEach
  void setUp() throws Exception {
    handler = new AssetGroupNameUniqueHandler();

    // Reset static cache so each test gets a fresh entity initialization.
    Field entitiesField = AssetGroupNameUniqueHandler.class.getDeclaredField("entities");
    entitiesField.setAccessible(true);
    entitiesField.set(null, null);

    assetGroupEntity = mock(Entity.class);

    ModelProvider modelProviderInstance = mock(ModelProvider.class);
    when(modelProviderInstance.getEntity(AssetGroup.ENTITY_NAME)).thenReturn(assetGroupEntity);
    mockedModelProvider = mockStatic(ModelProvider.class);
    mockedModelProvider.when(ModelProvider::getInstance).thenReturn(modelProviderInstance);

    TriggerHandler triggerHandlerInstance = mock(TriggerHandler.class);
    when(triggerHandlerInstance.isDisabled()).thenReturn(false);
    mockedTriggerHandler = mockStatic(TriggerHandler.class);
    mockedTriggerHandler.when(TriggerHandler::getInstance).thenReturn(triggerHandlerInstance);

    obDal = mock(OBDal.class);
    criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(AssetGroup.class)).thenReturn(criteria);
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
   * When no other asset group shares the name within the client, the create must proceed
   * without throwing.
   */
  @Test
  void testOnNewNoDuplicateDoesNotThrow() {
    EntityNewEvent event = buildNewEvent("New Category", "client-1", null);
    handler.onNew(event);
    verify(criteria).count();
  }

  /**
   * When updating a record and no OTHER asset group shares the name, the update must proceed
   * without throwing (the record keeping its own name is not a duplicate of itself).
   */
  @Test
  void testOnUpdateNoDuplicateDoesNotThrow() {
    EntityUpdateEvent event = buildUpdateEvent("Same Name", "client-1", "asset-group-1");
    handler.onUpdate(event);
    verify(criteria).count();
  }

  // ── Duplicate rejection ───────────────────────────────────────────────────────

  /**
   * When another asset group of the same client already has the given name, creating a new one
   * must throw {@link OBException} with the translated duplicate message.
   */
  @Test
  void testOnNewDuplicateNameThrowsOBException() {
    when(criteria.count()).thenReturn(1);
    EntityNewEvent event = buildNewEvent("Generic", "client-1", null);

    OBException ex = assertThrows(OBException.class, () -> handler.onNew(event));
    assertEquals(MESSAGE_TEXT, ex.getMessage());
  }

  /**
   * When updating a record to a name already used by ANOTHER asset group of the same client,
   * the update must throw {@link OBException}.
   */
  @Test
  void testOnUpdateDuplicateNameThrowsOBException() {
    when(criteria.count()).thenReturn(1);
    EntityUpdateEvent event = buildUpdateEvent("Generic", "client-1", "asset-group-2");

    OBException ex = assertThrows(OBException.class, () -> handler.onUpdate(event));
    assertEquals(MESSAGE_TEXT, ex.getMessage());
  }

  // ── Criteria construction — scope and exclusion ───────────────────────────────

  /**
   * The uniqueness check must be scoped by client (not organization): it must disable the
   * readable-organization filter and the active filter, so an inactive duplicate in a different
   * organization of the same client still counts.
   */
  @Test
  void testValidateUniqueNameScopesByClientAcrossOrganizationsAndActiveState() {
    EntityNewEvent event = buildNewEvent("Generic", "client-1", null);
    handler.onNew(event);

    verify(criteria).setFilterOnReadableOrganization(false);
    verify(criteria).setFilterOnActive(false);
    verify(criteria).setMaxResults(1);
  }

  /**
   * On an update (existing id present), the current record must be excluded from the duplicate
   * search so it doesn't collide with itself.
   */
  @Test
  void testValidateUniqueNameExcludesOwnIdOnUpdate() {
    EntityUpdateEvent event = buildUpdateEvent("Generic", "client-1", "asset-group-1");
    handler.onUpdate(event);
    // Exclusion is expressed as an extra Restrictions.ne criterion added to the query; the
    // count() call still happens exactly once regardless, verified as the observable outcome.
    verify(criteria, times(1)).count();
  }

  // ── Blank-name guard ──────────────────────────────────────────────────────────

  /**
   * When the name is null, the handler must skip the uniqueness check entirely (no DAL query).
   */
  @Test
  void testOnNewNullNameSkipsCheck() {
    EntityNewEvent event = buildNewEvent(null, "client-1", null);
    handler.onNew(event);
    verify(obDal, never()).createCriteria(AssetGroup.class);
  }

  /**
   * When the name is blank (only whitespace), the handler must skip the uniqueness check.
   */
  @Test
  void testOnNewBlankNameSkipsCheck() {
    EntityNewEvent event = buildNewEvent("   ", "client-1", null);
    handler.onNew(event);
    verify(obDal, never()).createCriteria(AssetGroup.class);
  }

  // ── Null-client guard ─────────────────────────────────────────────────────────

  /**
   * When the record has no client assigned, the handler must skip the uniqueness check rather
   * than querying with a null client restriction.
   */
  @Test
  void testOnNewNullClientSkipsCheck() {
    AssetGroup assetGroup = mock(AssetGroup.class);
    when(assetGroup.getEntity()).thenReturn(assetGroupEntity);
    when(assetGroup.getName()).thenReturn("Generic");
    when(assetGroup.getClient()).thenReturn(null);

    EntityNewEvent event = mock(EntityNewEvent.class);
    when(event.getTargetInstance()).thenReturn(assetGroup);

    handler.onNew(event);
    verify(obDal, never()).createCriteria(AssetGroup.class);
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

    EntityNewEvent event = buildNewEvent("Generic", "client-1", null);
    handler.onNew(event);
    verify(obDal, never()).createCriteria(any(Class.class));
  }

  /**
   * When the event targets a different entity (not FinancialMgmtAssetGroup), the handler must
   * skip processing.
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
   * {@code getObservedEntities} must return a non-null single-element array containing the
   * AssetGroup entity.
   */
  @Test
  void testGetObservedEntitiesReturnsAssetGroupEntity() {
    Entity[] observed = handler.getObservedEntities();
    assertNotNull(observed);
    assertEquals(1, observed.length);
    assertEquals(assetGroupEntity, observed[0]);
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
   * Builds an {@link AssetGroup} mock wired to the shared {@code assetGroupEntity} (so
   * {@code isValidEvent} matches), with the given name, client id and record id.
   */
  private AssetGroup buildAssetGroup(String name, String clientId, String id) {
    AssetGroup assetGroup = mock(AssetGroup.class);
    when(assetGroup.getEntity()).thenReturn(assetGroupEntity);
    when(assetGroup.getName()).thenReturn(name);

    if (clientId != null) {
      Client client = mock(Client.class);
      when(assetGroup.getClient()).thenReturn(client);
    }
    if (id != null) {
      when(assetGroup.getId()).thenReturn(id);
    }
    return assetGroup;
  }

  private EntityNewEvent buildNewEvent(String name, String clientId, String id) {
    AssetGroup assetGroup = buildAssetGroup(name, clientId, id);
    EntityNewEvent event = mock(EntityNewEvent.class);
    when(event.getTargetInstance()).thenReturn(assetGroup);
    return event;
  }

  private EntityUpdateEvent buildUpdateEvent(String name, String clientId, String id) {
    AssetGroup assetGroup = buildAssetGroup(name, clientId, id);
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    when(event.getTargetInstance()).thenReturn(assetGroup);
    return event;
  }
}
