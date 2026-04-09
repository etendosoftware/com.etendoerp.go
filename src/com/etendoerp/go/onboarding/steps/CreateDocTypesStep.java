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

package com.etendoerp.go.onboarding.steps;

import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.gl.GLCategory;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStep;
import com.etendoerp.go.onboarding.OnboardingStepException;

/**
 * Creates GL categories, document types, and auto-numbering sequences for the new client.
 *
 * <p>GL categories created: None, AR Invoice, AP Invoice, Material Management (all type D).
 *
 * <p>Document types and their sequences:
 * <ul>
 *   <li>Standard Order (SOO) — SO/ prefix, starts at 50000</li>
 *   <li>Purchase Order (POO) — PO/ prefix, starts at 800000</li>
 *   <li>AR Invoice (ARI)     — ARI/ prefix, starts at 100000</li>
 *   <li>AP Invoice (API)     — API/ prefix, starts at 200000</li>
 *   <li>MM Shipment (MMS)    — MMS/ prefix, starts at 500000</li>
 *   <li>MM Receipt (MMR)     — MMR/ prefix, starts at 600000</li>
 * </ul>
 */
public class CreateDocTypesStep implements OnboardingStep {

  private static final String GL_CATEGORY_NONE = "None";
  private static final String GL_CATEGORY_AR_INVOICE = "AR Invoice";
  private static final String GL_CATEGORY_AP_INVOICE = "AP Invoice";

  private static final class DocumentTypeDefinition {
    private final String name;
    private final String docBaseType;
    private final boolean salesTransaction;
    private final String soSubType;

    private DocumentTypeDefinition(String name, String docBaseType, boolean salesTransaction,
        String soSubType) {
      this.name = name;
      this.docBaseType = docBaseType;
      this.salesTransaction = salesTransaction;
      this.soSubType = soSubType;
    }
  }

  @Override
  public String name() {
    return "createDocTypes";
  }

  @Override
  public void execute(OnboardingContext ctx) throws OnboardingStepException {
    try {
      Client client = OBDal.getInstance().get(Client.class, ctx.getClientId());
      Organization org = OBDal.getInstance().get(Organization.class, ctx.getOrgId());
      if (client == null) {
        throw new OBException("Client not found with ID: " + ctx.getClientId());
      }
      if (org == null) {
        throw new OBException("Organization not found with ID: " + ctx.getOrgId());
      }

      // 1. Create GL categories
      GLCategory glNone = createGLCategory(client, org, GL_CATEGORY_NONE, "D");
      GLCategory glARInvoice = createGLCategory(client, org, GL_CATEGORY_AR_INVOICE, "D");
      GLCategory glAPInvoice = createGLCategory(client, org, GL_CATEGORY_AP_INVOICE, "D");
      GLCategory glMaterial = createGLCategory(client, org, "Material Management", "D");

      // 2. Create sequences and document types
      Sequence seqSO = createSequence(client, org, "Standard Order", "SO/", 50000L);
      createDocumentType(client, org,
        new DocumentTypeDefinition("Standard Order", "SOO", true, "SO"), seqSO, glNone);

      Sequence seqPO = createSequence(client, org, "Purchase Order", "PO/", 800000L);
      createDocumentType(client, org,
        new DocumentTypeDefinition("Purchase Order", "POO", false, null), seqPO, glNone);

      Sequence seqARI = createSequence(client, org, GL_CATEGORY_AR_INVOICE, "ARI/", 100000L);
      createDocumentType(client, org,
        new DocumentTypeDefinition(GL_CATEGORY_AR_INVOICE, "ARI", true, null), seqARI,
        glARInvoice);

      Sequence seqAPI = createSequence(client, org, GL_CATEGORY_AP_INVOICE, "API/", 200000L);
      createDocumentType(client, org,
        new DocumentTypeDefinition(GL_CATEGORY_AP_INVOICE, "API", false, null), seqAPI,
        glAPInvoice);

      Sequence seqMMS = createSequence(client, org, "MM Shipment", "MMS/", 500000L);
      createDocumentType(client, org,
        new DocumentTypeDefinition("MM Shipment", "MMS", true, null), seqMMS, glMaterial);

      Sequence seqMMR = createSequence(client, org, "MM Receipt", "MMR/", 600000L);
      createDocumentType(client, org,
        new DocumentTypeDefinition("MM Receipt", "MMR", false, null), seqMMR, glMaterial);
    } catch (OnboardingStepException e) {
      throw e;
    } catch (Exception e) {
      throw new OnboardingStepException(e.getMessage(), e);
    }
  }

  private GLCategory createGLCategory(Client client, Organization org, String name,
      String categoryType) {
    GLCategory glCategory = OBProvider.getInstance().get(GLCategory.class);
    glCategory.setNewOBObject(true);
    glCategory.setClient(client);
    glCategory.setOrganization(org);
    glCategory.setName(name);
    glCategory.setCategoryType(categoryType);
    glCategory.setDefault(false);
    OBDal.getInstance().save(glCategory);
    OBDal.getInstance().flush();
    return glCategory;
  }

  private Sequence createSequence(Client client, Organization org, String name, String prefix,
      long startNo) {
    Sequence sequence = OBProvider.getInstance().get(Sequence.class);
    sequence.setNewOBObject(true);
    sequence.setClient(client);
    sequence.setOrganization(org);
    sequence.setName(name);
    sequence.setPrefix(prefix);
    sequence.setStartingNo(startNo);
    sequence.setNextAssignedNumber(startNo);
    sequence.setIncrementBy(1L);
    sequence.setAutoNumbering(true);
    OBDal.getInstance().save(sequence);
    return sequence;
  }

  private DocumentType createDocumentType(Client client, Organization org,
      DocumentTypeDefinition definition, Sequence sequence, GLCategory glCategory) {
    DocumentType docType = OBProvider.getInstance().get(DocumentType.class);
    docType.setNewOBObject(true);
    docType.setClient(client);
    docType.setOrganization(org);
    docType.setName(definition.name);
    docType.setPrintText(definition.name);
    docType.setDocumentCategory(definition.docBaseType);
    docType.setSalesTransaction(definition.salesTransaction);
    docType.setSOSubType(definition.soSubType);
    docType.setSequencedDocument(true);
    docType.setDocumentSequence(sequence);
    docType.setGLCategory(glCategory);
    docType.setDefault(true);
    OBDal.getInstance().save(docType);
    return docType;
  }
}
