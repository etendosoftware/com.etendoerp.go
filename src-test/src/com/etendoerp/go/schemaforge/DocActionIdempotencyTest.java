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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.util.NeoMessages;

/**
 * ETP-5783 — a document action to a status the document is already in is answered
 * {@code 200 alreadyApplied} without running the procedure; anything undecidable runs it as today.
 *
 * <p>The status column is found through the AD (a List column whose reference list holds the draft
 * status and the target status), never through a property name: the fixture's status property is
 * called {@code lifecycle} on purpose.
 *
 * @covers com.etendoerp.go.schemaforge.DocActionIdempotency
 */
class DocActionIdempotencyTest {

  private static final String TAB_ID = "tab-1";
  private static final String RECORD_ID = "rec-1";
  private static final String LIST_REFERENCE_ID = "17";

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<DocActionResolution> resolutionStatic;
  private MockedStatic<NeoMessages> messagesStatic;
  private OBDal dal;
  private BaseOBObject document;
  private Table table;
  private OBCriteria<org.openbravo.model.ad.domain.List> listCriteria;
  private final List<org.openbravo.model.ad.domain.List> listValues = new java.util.ArrayList<>();

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    resolutionStatic = mockStatic(DocActionResolution.class);
    messagesStatic = mockStatic(NeoMessages.class);
    messagesStatic.when(() -> NeoMessages.tr("ETGO_DocActionAlreadyApplied"))
        .thenReturn("El documento ya está en ese estado.");

    document = mock(BaseOBObject.class);
    resolutionStatic.when(() -> DocActionResolution.loadDocActionRecord(TAB_ID, RECORD_ID))
        .thenReturn(document);
    Tab tab = mock(Tab.class);
    table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(dal.get(Tab.class, TAB_ID)).thenReturn(tab);
    listCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(org.openbravo.model.ad.domain.List.class)).thenReturn(listCriteria);
    when(listCriteria.list()).thenAnswer(inv -> listValues);
  }

  @AfterEach
  void tearDown() {
    messagesStatic.close();
    resolutionStatic.close();
    obDalStatic.close();
  }

  /** One active List column on the table, with the property it maps to holding {@code status}. */
  private void tableWithStatusColumn(String status) {
    Column column = listColumn("DocStatus", "ref-status");
    when(table.getADColumnList()).thenReturn(List.of(column));
    bindProperty(column, "lifecycle", status);
    listValues("ref-status", "DR", status);
  }

  private void listValues(String referenceId, String... keys) {
    for (String key : keys) {
      org.openbravo.model.ad.domain.List value = mock(org.openbravo.model.ad.domain.List.class);
      Reference reference = mock(Reference.class);
      when(reference.getId()).thenReturn(referenceId);
      when(value.getReference()).thenReturn(reference);
      when(value.getSearchKey()).thenReturn(key);
      listValues.add(value);
    }
  }

  private static Column listColumn(String dbName, String referenceValueId) {
    Column column = mock(Column.class);
    Reference type = mock(Reference.class);
    Reference values = mock(Reference.class);
    when(type.getId()).thenReturn(LIST_REFERENCE_ID);
    when(values.getId()).thenReturn(referenceValueId);
    when(column.isActive()).thenReturn(true);
    when(column.getReference()).thenReturn(type);
    when(column.getReferenceSearchKey()).thenReturn(values);
    when(column.getDBColumnName()).thenReturn(dbName);
    return column;
  }

  private void bindProperty(Column column, String propertyName, String value) {
    Entity entity = mock(Entity.class);
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(propertyName);
    when(entity.getPropertyByColumnName(column.getDBColumnName(), false)).thenReturn(property);
    when(document.getEntity()).thenReturn(entity);
    when(document.get(propertyName)).thenReturn(value);
  }

  @ParameterizedTest(name = "{0} on a document already in {0}")
  @CsvSource({ "CO", "VO", "CL" })
  void aDocumentAlreadyInTheTargetStatusIsAnsweredWithoutRunningAnything(String action)
      throws Exception {
    tableWithStatusColumn(action);

    NeoResponse response = DocActionIdempotency.alreadyApplied(TAB_ID, RECORD_ID, action);

    assertNotNull(response);
    assertEquals(200, response.getHttpStatus());
    assertEquals("success", response.getBody().getString("status"));
    assertTrue(response.getBody().getBoolean("alreadyApplied"));
    assertEquals("El documento ya está en ese estado.", response.getBody().getString("message"));
  }

  @Test
  void aDocumentInAnotherStatusGoesToTheProcedure() {
    tableWithStatusColumn("DR");

    assertNull(DocActionIdempotency.alreadyApplied(TAB_ID, RECORD_ID, "CO"));
  }

  /** Only "already there" is success: a completed document asked to void is not decided here. */
  @Test
  void aCompletedDocumentAskedToVoidGoesToTheProcedure() {
    tableWithStatusColumn("CO");

    assertNull(DocActionIdempotency.alreadyApplied(TAB_ID, RECORD_ID, "VO"));
  }

  /** RE is a status of its own ("Reversed") and leads to draft: ambiguous, never decided here. */
  @ParameterizedTest(name = "{0} is left to the procedure")
  @CsvSource({ "RE", "PR", "AP", "RJ", "RC", "XL", "re", "co", "' CO'" })
  void actionsWithoutAnUnambiguousTargetAreLeftToTheProcedure(String action) {
    tableWithStatusColumn("RE");

    assertNull(DocActionIdempotency.alreadyApplied(TAB_ID, RECORD_ID, action));
    resolutionStatic.verify(() -> DocActionResolution.loadDocActionRecord(anyString(), anyString()),
        never());
  }

  /** The list must hold the draft status and the target: otherwise it is not a status list. */
  @Test
  void aListWithoutBothStatusesIsNotTheStatusColumn() {
    tableWithStatusColumn("CO");
    listValues.remove(0); // only the target status is in the list, no draft

    assertNull(DocActionIdempotency.alreadyApplied(TAB_ID, RECORD_ID, "CO"));
  }

  @Test
  void twoCandidateColumnsAreAmbiguousAndGoToTheProcedure() {
    Column first = listColumn("DocStatus", "ref-a");
    Column second = listColumn("OtherStatus", "ref-b");
    when(table.getADColumnList()).thenReturn(List.of(first, second));
    bindProperty(first, "lifecycle", "CO");
    listValues("ref-a", "DR", "CO");
    listValues("ref-b", "DR", "CO");
    Entity entity = document.getEntity();
    Property other = mock(Property.class);
    when(other.getName()).thenReturn("other");
    when(entity.getPropertyByColumnName("OtherStatus", false)).thenReturn(other);

    assertNull(DocActionIdempotency.alreadyApplied(TAB_ID, RECORD_ID, "CO"));
  }

  @Test
  void aTableWithoutAStatusColumnGoesToTheProcedure() {
    when(table.getADColumnList()).thenReturn(List.of());

    assertNull(DocActionIdempotency.alreadyApplied(TAB_ID, RECORD_ID, "CO"));
  }

  @Test
  void anInactiveOrNonListColumnIsNotTheStatusColumn() {
    Column inactive = listColumn("DocStatus", "ref-a");
    when(inactive.isActive()).thenReturn(false);
    Column notAList = listColumn("Other", "ref-b");
    when(notAList.getReference().getId()).thenReturn("10");
    when(table.getADColumnList()).thenReturn(List.of(inactive, notAList));

    assertNull(DocActionIdempotency.alreadyApplied(TAB_ID, RECORD_ID, "CO"));
  }

  @Test
  void aMissingRecordGoesToTheProcedure() {
    resolutionStatic.when(() -> DocActionResolution.loadDocActionRecord(TAB_ID, "ghost"))
        .thenReturn(null);

    assertNull(DocActionIdempotency.alreadyApplied(TAB_ID, "ghost", "CO"));
  }

  /** A lookup that fails must never read as "already applied": the procedure speaks. */
  @Test
  void aFailingLookupGoesToTheProcedure() {
    resolutionStatic.when(() -> DocActionResolution.loadDocActionRecord(TAB_ID, RECORD_ID))
        .thenThrow(new IllegalStateException("boom"));

    assertNull(DocActionIdempotency.alreadyApplied(TAB_ID, RECORD_ID, "CO"));
  }

  /** The message falls back to English when the AD message is not installed (key echoed back). */
  @Test
  void theMessageFallsBackWhenTheAdMessageIsMissing() throws Exception {
    tableWithStatusColumn("CO");
    messagesStatic.when(() -> NeoMessages.tr("ETGO_DocActionAlreadyApplied"))
        .thenReturn("ETGO_DocActionAlreadyApplied");

    NeoResponse response = DocActionIdempotency.alreadyApplied(TAB_ID, RECORD_ID, "CO");

    assertEquals("The document is already in the requested status.",
        response.getBody().getString("message"));
  }

  // ---- refusal re-read (a concurrent request reached the target status first) ----

  private static NeoResponse refusal422() throws Exception {
    return DocActionRefusal.businessRule(new org.codehaus.jettison.json.JSONObject()
        .put("status", "error").put("message", "Document already processed"));
  }

  @Test
  void aRefusalIsAnAlreadyAppliedWhenTheStatusIsNowTheTarget() throws Exception {
    tableWithStatusColumn("CO");

    NeoResponse response =
        DocActionIdempotency.orAlreadyApplied(refusal422(), TAB_ID, RECORD_ID, "CO");

    assertEquals(200, response.getHttpStatus());
    assertTrue(response.getBody().getBoolean("alreadyApplied"));
    org.mockito.Mockito.verify(dal).refresh(document);
  }

  @Test
  void aRefusalStandsWhenTheStatusIsNotTheTarget() throws Exception {
    tableWithStatusColumn("DR");
    NeoResponse refusal = refusal422();

    assertEquals(refusal,
        DocActionIdempotency.orAlreadyApplied(refusal, TAB_ID, RECORD_ID, "CO"));
  }

  @Test
  void anAnswerThatIsNotARefusalIsNeverRewritten() throws Exception {
    tableWithStatusColumn("CO");
    NeoResponse ok = NeoResponse.ok(new org.codehaus.jettison.json.JSONObject());
    NeoResponse badRequest = NeoResponse.error(400, "no");

    assertEquals(ok, DocActionIdempotency.orAlreadyApplied(ok, TAB_ID, RECORD_ID, "CO"));
    assertEquals(badRequest,
        DocActionIdempotency.orAlreadyApplied(badRequest, TAB_ID, RECORD_ID, "CO"));
    org.mockito.Mockito.verify(dal, never()).refresh(any());
  }
}
