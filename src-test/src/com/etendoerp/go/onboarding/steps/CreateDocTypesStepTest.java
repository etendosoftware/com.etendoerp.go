/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.onboarding.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.gl.GLCategory;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStepException;

/**
 * Unit tests for {@link CreateDocTypesStep}.
 *
 * <p>Covers: name(), null-client/org error paths, successful creation of
 * 5 GL categories + 7 sequences + 7 document types, correct number of
 * OBDal.save and flush calls, and GL category resolution for each
 * document type.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateDocTypesStepTest {

  private CreateDocTypesStep step;

  @Mock private OBDal obDal;
  @Mock private OBProvider obProvider;
  @Mock private Client client;
  @Mock private Organization org;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBProvider> obProviderMock;

  /** GL category mocks returned by OBProvider in creation order. */
  private final List<GLCategory> glCategories = new ArrayList<>();
  /** Sequence mocks returned by OBProvider in creation order. */
  private final List<Sequence> sequences = new ArrayList<>();
  /** DocumentType mocks returned by OBProvider in creation order. */
  private final List<DocumentType> docTypes = new ArrayList<>();

  @BeforeEach
  void setUp() {
    step = new CreateDocTypesStep();
    obDalMock = mockStatic(OBDal.class);
    obProviderMock = mockStatic(OBProvider.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (obProviderMock != null) {
      obProviderMock.close();
    }
  }

  // ─── name ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("name() returns createDocTypes")
  void nameReturnsCreateDocTypes() {
    assertEquals("createDocTypes", step.name());
  }

  // ─── execute: error paths ───────────────────────────────────────────

  @Nested
  @DisplayName("execute error paths")
  class ExecuteErrors {

    @Test
    @DisplayName("null client throws OnboardingStepException")
    void nullClientThrowsOnboardingStepException() {
      OnboardingContext ctx = new OnboardingContext();
      ctx.setClientId("bad-client");
      ctx.setOrgId("org-1");
      when(obDal.get(Client.class, "bad-client")).thenReturn(null);
      when(obDal.get(Organization.class, "org-1")).thenReturn(mock(Organization.class));

      OnboardingStepException ex = assertThrows(OnboardingStepException.class,
          () -> step.execute(ctx));
      assertTrue(ex.getMessage().contains("Client not found"));
    }

    @Test
    @DisplayName("null org throws OnboardingStepException")
    void nullOrgThrowsOnboardingStepException() {
      OnboardingContext ctx = new OnboardingContext();
      ctx.setClientId("client-1");
      ctx.setOrgId("bad-org");
      when(obDal.get(Client.class, "client-1")).thenReturn(client);
      when(obDal.get(Organization.class, "bad-org")).thenReturn(null);

      OnboardingStepException ex = assertThrows(OnboardingStepException.class,
          () -> step.execute(ctx));
      assertTrue(ex.getMessage().contains("Organization not found"));
    }
  }

  // ─── execute: happy path ───────────────────────────────────────────

  @Nested
  @DisplayName("execute happy path")
  class ExecuteHappyPath {

    @BeforeEach
    void setUpMocks() {
      when(obDal.get(Client.class, "client-1")).thenReturn(client);
      when(obDal.get(Organization.class, "org-1")).thenReturn(org);

      // OBProvider returns fresh mocks for each get() call.
      // Order: 5 GLCategory, then alternating (Sequence, DocumentType) x7
      stubGlCategoryCreation(5);
      stubSequenceCreation(7);
      stubDocTypeCreation(7);
    }

    @Test
    @DisplayName("successful execute saves 5 GL categories, 7 sequences, and 7 doc types")
    void successfulExecuteSavesAllEntities() throws OnboardingStepException {
      step.execute(buildValidContext());

      // 5 GL categories + 7 sequences + 7 doc types = 19 saves
      verify(obDal, times(19)).save(any());
    }

    @Test
    @DisplayName("flush is called after each GL category creation")
    void flushCalledAfterEachGlCategory() throws OnboardingStepException {
      step.execute(buildValidContext());

      // flush is called once per GL category = 5 times
      verify(obDal, times(5)).flush();
    }

    @Test
    @DisplayName("5 GL categories are saved with correct names")
    void glCategoriesSavedWithCorrectNames() throws OnboardingStepException {
      step.execute(buildValidContext());

      verify(glCategories.get(0)).setName("None");
      verify(glCategories.get(1)).setName("AR Invoice");
      verify(glCategories.get(2)).setName("AP Invoice");
      verify(glCategories.get(3)).setName("Material Management");
      verify(glCategories.get(4)).setName("Bank Statement");
    }

    @Test
    @DisplayName("all GL categories have categoryType D")
    void glCategoriesHaveCategoryTypeD() throws OnboardingStepException {
      step.execute(buildValidContext());

      for (GLCategory gl : glCategories) {
        verify(gl).setCategoryType("D");
      }
    }

    @Test
    @DisplayName("all GL categories have client and org set")
    void glCategoriesHaveClientAndOrg() throws OnboardingStepException {
      step.execute(buildValidContext());

      for (GLCategory gl : glCategories) {
        verify(gl).setClient(client);
        verify(gl).setOrganization(org);
      }
    }

    @Test
    @DisplayName("7 sequences are saved with correct prefixes")
    void sequencesSavedWithCorrectPrefixes() throws OnboardingStepException {
      step.execute(buildValidContext());

      verify(sequences.get(0)).setPrefix("SO/");
      verify(sequences.get(1)).setPrefix("PO/");
      verify(sequences.get(2)).setPrefix("ARI/");
      verify(sequences.get(3)).setPrefix("API/");
      verify(sequences.get(4)).setPrefix("MMS/");
      verify(sequences.get(5)).setPrefix("MMR/");
      verify(sequences.get(6)).setPrefix("");
    }

    @Test
    @DisplayName("7 sequences are saved with correct start numbers")
    void sequencesSavedWithCorrectStartNumbers() throws OnboardingStepException {
      step.execute(buildValidContext());

      verify(sequences.get(0)).setStartingNo(50000L);
      verify(sequences.get(1)).setStartingNo(800000L);
      verify(sequences.get(2)).setStartingNo(100000L);
      verify(sequences.get(3)).setStartingNo(200000L);
      verify(sequences.get(4)).setStartingNo(500000L);
      verify(sequences.get(5)).setStartingNo(600000L);
      verify(sequences.get(6)).setStartingNo(1000000L);
    }

    @Test
    @DisplayName("all sequences have autoNumbering enabled")
    void sequencesHaveAutoNumberingEnabled() throws OnboardingStepException {
      step.execute(buildValidContext());

      for (Sequence seq : sequences) {
        verify(seq).setAutoNumbering(true);
      }
    }

    @Test
    @DisplayName("7 document types are saved with correct names")
    void docTypesSavedWithCorrectNames() throws OnboardingStepException {
      step.execute(buildValidContext());

      verify(docTypes.get(0)).setName("Standard Order");
      verify(docTypes.get(1)).setName("Purchase Order");
      verify(docTypes.get(2)).setName("AR Invoice");
      verify(docTypes.get(3)).setName("AP Invoice");
      verify(docTypes.get(4)).setName("MM Shipment");
      verify(docTypes.get(5)).setName("MM Receipt");
      verify(docTypes.get(6)).setName("Bank Statement File");
    }

    @Test
    @DisplayName("document types have correct document categories")
    void docTypesHaveCorrectDocumentCategories() throws OnboardingStepException {
      step.execute(buildValidContext());

      verify(docTypes.get(0)).setDocumentCategory("SOO");
      verify(docTypes.get(1)).setDocumentCategory("POO");
      verify(docTypes.get(2)).setDocumentCategory("ARI");
      verify(docTypes.get(3)).setDocumentCategory("API");
      verify(docTypes.get(4)).setDocumentCategory("MMS");
      verify(docTypes.get(5)).setDocumentCategory("MMR");
      verify(docTypes.get(6)).setDocumentCategory("BSF");
    }

    @Test
    @DisplayName("document types have correct salesTransaction flags")
    void docTypesHaveCorrectSalesFlags() throws OnboardingStepException {
      step.execute(buildValidContext());

      verify(docTypes.get(0)).setSalesTransaction(true);   // Standard Order
      verify(docTypes.get(1)).setSalesTransaction(false);  // Purchase Order
      verify(docTypes.get(2)).setSalesTransaction(true);   // AR Invoice
      verify(docTypes.get(3)).setSalesTransaction(false);  // AP Invoice
      verify(docTypes.get(4)).setSalesTransaction(true);   // MM Shipment
      verify(docTypes.get(5)).setSalesTransaction(false);  // MM Receipt
      verify(docTypes.get(6)).setSalesTransaction(false);  // Bank Statement File
    }

    @Test
    @DisplayName("Standard Order has SO subtype, Purchase Order and Bank Statement File have null")
    void soSubTypeSetCorrectly() throws OnboardingStepException {
      step.execute(buildValidContext());

      verify(docTypes.get(0)).setSOSubType("SO");
      verify(docTypes.get(1)).setSOSubType(null);
      verify(docTypes.get(6)).setSOSubType(null);  // Bank Statement File
    }

    @Test
    @DisplayName("all document types are sequenced and default")
    void docTypesAreSequencedAndDefault() throws OnboardingStepException {
      step.execute(buildValidContext());

      for (DocumentType dt : docTypes) {
        verify(dt).setSequencedDocument(true);
        verify(dt).setDefault(true);
      }
    }

    @Test
    @DisplayName("each document type is linked to its corresponding sequence")
    void docTypesLinkedToCorrectSequences() throws OnboardingStepException {
      step.execute(buildValidContext());

      for (int i = 0; i < 7; i++) {
        verify(docTypes.get(i)).setDocumentSequence(sequences.get(i));
      }
    }

    // ─── resolveGlCategory via full flow ─────────────────────────────

    @Test
    @DisplayName("Standard Order and Purchase Order use GL category None")
    void standardOrderAndPurchaseOrderUseGlNone() throws OnboardingStepException {
      step.execute(buildValidContext());

      GLCategory glNone = glCategories.get(0);
      verify(docTypes.get(0)).setGLCategory(glNone);  // Standard Order -> NONE
      verify(docTypes.get(1)).setGLCategory(glNone);  // Purchase Order -> NONE
    }

    @Test
    @DisplayName("AR Invoice doc type uses GL category AR Invoice")
    void arInvoiceUsesGlAr() throws OnboardingStepException {
      step.execute(buildValidContext());

      GLCategory glAR = glCategories.get(1);
      verify(docTypes.get(2)).setGLCategory(glAR);
    }

    @Test
    @DisplayName("AP Invoice doc type uses GL category AP Invoice")
    void apInvoiceUsesGlAp() throws OnboardingStepException {
      step.execute(buildValidContext());

      GLCategory glAP = glCategories.get(2);
      verify(docTypes.get(3)).setGLCategory(glAP);
    }

    @Test
    @DisplayName("MM Shipment and MM Receipt use GL category Material Management")
    void mmShipmentAndReceiptUseGlMaterial() throws OnboardingStepException {
      step.execute(buildValidContext());

      GLCategory glMaterial = glCategories.get(3);
      verify(docTypes.get(4)).setGLCategory(glMaterial);  // MM Shipment -> MATERIAL
      verify(docTypes.get(5)).setGLCategory(glMaterial);  // MM Receipt  -> MATERIAL
    }

    @Test
    @DisplayName("Bank Statement File doc type uses GL category Bank Statement")
    void bankStatementFileUsesGlBankStatement() throws OnboardingStepException {
      step.execute(buildValidContext());

      GLCategory glBankStatement = glCategories.get(4);
      verify(docTypes.get(6)).setGLCategory(glBankStatement);  // Bank Statement File -> BANK_STATEMENT
    }

    // ─── helpers ─────────────────────────────────────────────────────

    /**
     * Stubs OBProvider.get(GLCategory.class) to return a fresh mock
     * for each of the expected {@code count} invocations.
     */
    private void stubGlCategoryCreation(int count) {
      GLCategory first = mock(GLCategory.class);
      glCategories.add(first);
      GLCategory[] rest = new GLCategory[count - 1];
      for (int i = 0; i < count - 1; i++) {
        rest[i] = mock(GLCategory.class);
        glCategories.add(rest[i]);
      }
      when(obProvider.get(GLCategory.class)).thenReturn(first, rest);
    }

    /**
     * Stubs OBProvider.get(Sequence.class) to return a fresh mock
     * for each of the expected {@code count} invocations.
     */
    private void stubSequenceCreation(int count) {
      Sequence first = mock(Sequence.class);
      sequences.add(first);
      Sequence[] rest = new Sequence[count - 1];
      for (int i = 0; i < count - 1; i++) {
        rest[i] = mock(Sequence.class);
        sequences.add(rest[i]);
      }
      when(obProvider.get(Sequence.class)).thenReturn(first, rest);
    }

    /**
     * Stubs OBProvider.get(DocumentType.class) to return a fresh mock
     * for each of the expected {@code count} invocations.
     */
    private void stubDocTypeCreation(int count) {
      DocumentType first = mock(DocumentType.class);
      docTypes.add(first);
      DocumentType[] rest = new DocumentType[count - 1];
      for (int i = 0; i < count - 1; i++) {
        rest[i] = mock(DocumentType.class);
        docTypes.add(rest[i]);
      }
      when(obProvider.get(DocumentType.class)).thenReturn(first, rest);
    }
  }

  // ─── shared helpers ──────────────────────────────────────────────────

  private OnboardingContext buildValidContext() {
    OnboardingContext ctx = new OnboardingContext();
    ctx.setClientId("client-1");
    ctx.setOrgId("org-1");
    return ctx;
  }
}
