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
package com.etendoerp.go.onboarding;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Tree;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationAcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.Element;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Wires the newly created organization to the general ledger that the onboarding dataset import
 * brings in (Gap A1).
 *
 * <p>The dataset import provisions the chart of accounts and the accounting schema at client level
 * ({@code AD_ORG_ID = '0'}) and creates the {@code AD_ORG_ACCTSCHEMA} link remapped to the target
 * organization. It does NOT set {@code AD_ORG.C_ACCTSCHEMA_ID} (the organization's general ledger
 * pointer), because {@code AD_ORG} is excluded from the import. This service closes that gap by
 * pointing the organization at the imported schema and defensively ensuring the link row exists.
 *
 * <p>Calendar/period wiring is intentionally out of scope here — that belongs to the period-control
 * gap (C1), not A1.
 */
public class OnboardingAccountingWiringService {

  private static final Logger log = LogManager.getLogger(OnboardingAccountingWiringService.class);

  /** AD_Tree.TreeType for the account-element (chart of accounts) tree. */
  private static final String ELEMENT_VALUE_TREE_TYPE = "EV";

  /** Generic-organization id; client-level chart records and their tree nodes live here. */
  private static final String GENERIC_ORG_ID = "0";

  /**
   * Source AD_Tree id of GOClient's chart-of-accounts (EV) tree as it ships in the bundled
   * {@code AD_TREENODE.xml}. The hierarchy is read from this tree only; the org-specific orphan tree
   * GOClient also ships is ignored (see {@code AccountElementTreeFilter}).
   */
  private static final String SOURCE_ELEMENT_TREE_ID = "D937A98591DC4F6386C8130D350B17C7";

  /** Classpath root where {@code prepareOnboardingSampledata} stages the GOClient sourcedata XML. */
  private static final String SAMPLE_DATA_RESOURCE_DIRECTORY =
      "com/etendoerp/go/onboarding/sampledata/GOClient";
  private static final String SOURCE_ELEMENT_VALUE_RESOURCE =
      SAMPLE_DATA_RESOURCE_DIRECTORY + "/C_ELEMENTVALUE.xml";
  private static final String SOURCE_TREENODE_RESOURCE =
      SAMPLE_DATA_RESOURCE_DIRECTORY + "/AD_TREENODE.xml";

  /**
   * Inserts one account-element tree node, mapping the bundled hierarchy onto the tenant's own
   * records. {@code node_id}/{@code parent_id} carry the tenant {@code C_ELEMENTVALUE} ids (resolved
   * by account {@code value}); a root node uses {@code parent_id = '0'}. The {@code NOT EXISTS} guard
   * makes the insert idempotent, so re-running onboarding never duplicates the tree.
   */
  private static final String ELEMENT_TREENODE_SQL =
      "INSERT INTO ad_treenode (ad_treenode_id, ad_tree_id, node_id, ad_client_id, ad_org_id,"
      + " isactive, created, createdby, updated, updatedby, parent_id, seqno)"
      + " SELECT get_uuid(), :treeId, :nodeId, :clientId, '0', 'Y', now(), '0', now(), '0',"
      + " :parentId, :seqno"
      + " WHERE NOT EXISTS (SELECT 1 FROM ad_treenode t"
      + " WHERE t.ad_tree_id = :treeId AND t.node_id = :nodeId)";

  /**
   * Points the target organization at the imported accounting schema and ensures the
   * organization↔schema link exists.
   *
   * @param clientId    target client identifier
   * @param orgId       target organization identifier
   * @param adminUserId administrator user for DAL context
   * @param adminRoleId administrator role for DAL context
   */
  public void wire(String clientId, String orgId, String adminUserId, String adminRoleId) {
    validateContext(clientId, orgId, adminUserId, adminRoleId);
    OBContext previousContext = captureCurrentContext();
    applyExecutionContext(adminUserId, adminRoleId, clientId, orgId);
    try {
      enterAdminMode();
      try {
        Client client = resolveClient(clientId);
        Organization org = resolveOrganization(orgId);
        if (client == null) {
          throw new OBException("Client not found for accounting wiring: " + clientId);
        }
        if (org == null) {
          throw new OBException("Organization not found for accounting wiring: " + orgId);
        }
        AcctSchema ledger = resolveImportedLedger(client);
        if (ledger == null) {
          throw new OBException("No accounting schema was imported for client " + clientId
              + "; cannot wire the organization general ledger");
        }
        wireOrganizationGeneralLedger(org, ledger);
        ensureOrganizationAcctSchema(client, org, ledger);
        wireAccountElementTree(client);
        rebrandImportedChartNames(client, ledger);
        provisionEntityPostingAccounts(client, ledger);
        flushChanges();
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
    }
  }

  /**
   * Provisions the per-business-partner posting accounts (Gap A2) once the tenant's business
   * partners exist.
   *
   * <p>This is a SECOND, later entry point on purpose. {@link #wire} runs early in the onboarding
   * chain (right after the dataset import) so it can provision the {@code *_acct} rows for the
   * entities the import brings in (BP groups, product categories, products). But the tenant's first
   * business partner — the default customer — is created by {@code OnboardingDefaultCustomerService}
   * several steps LATER, and {@code C_BPARTNER} is not part of the imported dataset. If the per-BP
   * accounts were provisioned only inside {@link #wire}, {@code C_BP_CUSTOMER_ACCT} (and
   * {@code C_BP_VENDOR_ACCT}) would always be empty for a fresh tenant. The onboarding servlet
   * therefore calls this method again AFTER {@code ensureDefaultCustomer}, when the customer row
   * exists and is flushed in the same transaction.
   *
   * <p>Both inserts are idempotent ({@code NOT EXISTS} guards), so the earlier no-op run inside
   * {@link #wire} and this run never collide.
   *
   * @param clientId    target client identifier
   * @param orgId       target organization identifier
   * @param adminUserId administrator user for DAL context
   * @param adminRoleId administrator role for DAL context
   */
  public void wireBusinessPartnerAccounts(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    validateContext(clientId, orgId, adminUserId, adminRoleId);
    OBContext previousContext = captureCurrentContext();
    applyExecutionContext(adminUserId, adminRoleId, clientId, orgId);
    try {
      enterAdminMode();
      try {
        Client client = resolveClient(clientId);
        if (client == null) {
          throw new OBException("Client not found for business-partner accounting: " + clientId);
        }
        AcctSchema ledger = resolveImportedLedger(client);
        if (ledger == null) {
          throw new OBException("No accounting schema was imported for client " + clientId
              + "; cannot provision business-partner posting accounts");
        }
        runEntityAcctInsert(BP_CUSTOMER_ACCT_SQL, clientId, ledger.getId());
        runEntityAcctInsert(BP_VENDOR_ACCT_SQL, clientId, ledger.getId());
        flushChanges();
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
    }
  }

  /**
   * Resolves the accounting schema imported for the client. GOClient ships exactly one schema; if
   * more than one is present the first by id is used and a warning is logged.
   */
  protected AcctSchema resolveImportedLedger(Client client) {
    OBCriteria<AcctSchema> criteria = OBDal.getInstance().createCriteria(AcctSchema.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(AcctSchema.PROPERTY_CLIENT, client));
    criteria.addOrderBy(AcctSchema.PROPERTY_ID, true);
    criteria.setMaxResults(2);
    java.util.List<AcctSchema> schemas = criteria.list();
    if (schemas.isEmpty()) {
      return null;
    }
    if (schemas.size() > 1) {
      log.warn("Client {} has more than one accounting schema after import; using {}",
          client.getId(), schemas.get(0).getId());
    }
    return schemas.get(0);
  }

  protected void wireOrganizationGeneralLedger(Organization org, AcctSchema ledger) {
    if (org.getGeneralLedger() != null) {
      return;
    }
    org.setGeneralLedger(ledger);
    OBDal.getInstance().save(org);
  }

  protected void ensureOrganizationAcctSchema(Client client, Organization org, AcctSchema ledger) {
    OBCriteria<OrganizationAcctSchema> criteria = OBDal.getInstance()
        .createCriteria(OrganizationAcctSchema.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(OrganizationAcctSchema.PROPERTY_ORGANIZATION, org));
    criteria.add(Restrictions.eq(OrganizationAcctSchema.PROPERTY_ACCOUNTINGSCHEMA, ledger));
    criteria.setMaxResults(1);
    if (criteria.uniqueResult() != null) {
      return;
    }
    OrganizationAcctSchema link = OBProvider.getInstance().get(OrganizationAcctSchema.class);
    link.setNewOBObject(true);
    link.setClient(client);
    link.setOrganization(org);
    link.setAccountingSchema(ledger);
    OBDal.getInstance().save(link);
  }

  /**
   * Points the imported chart element(s) at the tenant's own account-element (EV) tree and rebuilds
   * the account hierarchy under it (Gap B2).
   *
   * <p>{@code C_ELEMENT.AD_TREE_ID} is stripped during normalization (it pointed at GOClient's tree,
   * a cross-tenant reference). Each tenant has its own EV tree auto-created at client creation; this
   * re-points the imported element at that tree.
   *
   * <p>The dataset import also drops {@code AD_TREE}/{@code AD_TREENODE} (both excluded), so the
   * imported chart arrives flat: 1790 accounts with no parent/child placement, breaking tree-walking
   * reports (Balance Sheet, P&amp;L) and summary-account roll-ups. This method reconstructs the
   * hierarchy from the bundled {@code AD_TREENODE.xml}. That XML references GOClient's <em>source</em>
   * {@code C_ELEMENTVALUE} ids, which do not survive the import (Etendo mints fresh ids); the only
   * stable join key is the account {@code value} (the account code, unique across all 1790 accounts).
   * So the source ids are bridged to {@code value} via the bundled {@code C_ELEMENTVALUE.xml}, then
   * resolved against the tenant's own accounts by {@code value}. This is the onboarding-side twin of
   * step 13 of the {@code R1-chart-of-accounts} corrective data-fix (which can use the data-fix
   * runner's {@code @uuid_} placeholder scheme; live onboarding cannot, hence the value join).
   */
  protected void wireAccountElementTree(Client client) {
    Tree tree = resolveTenantElementValueTree(client);
    if (tree == null) {
      log.warn("Client {} has no EV (account element) tree; leaving imported elements untree'd",
          client.getId());
      return;
    }
    OBCriteria<Element> criteria = OBDal.getInstance().createCriteria(Element.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Element.PROPERTY_CLIENT, client));
    criteria.add(Restrictions.isNull(Element.PROPERTY_TREE));
    for (Element element : criteria.list()) {
      element.setTree(tree);
      OBDal.getInstance().save(element);
    }
    provisionElementTreeNodes(client, tree);
  }

  /**
   * Rebuilds the chart-of-accounts hierarchy under the tenant's EV {@code tree} from the bundled
   * GOClient sourcedata, joining the source tree to the tenant's accounts by {@code value}.
   *
   * <p>Source nodes whose account is not present in this tenant are skipped. A node whose parent
   * account cannot be resolved is attached to the root ({@code parent_id = '0'}) and logged, so the
   * tree always stays walkable even if the bundled data drifts. The work is idempotent.
   */
  protected void provisionElementTreeNodes(Client client, Tree tree) {
    List<SourceTreeNode> sourceNodes = loadSourceTreeNodes();
    if (sourceNodes.isEmpty()) {
      log.warn("No bundled account-element tree nodes found on the classpath ({}); chart of accounts"
          + " stays flat for client {}", SOURCE_TREENODE_RESOURCE, client.getId());
      return;
    }
    Map<String, String> sourceIdToValue = loadSourceElementValues();
    Map<String, String> tenantValueToId = loadTenantElementValueIds(client);
    String treeId = tree.getId();
    String clientId = client.getId();
    int inserted = 0;
    int skipped = 0;
    for (SourceTreeNode node : sourceNodes) {
      String childValue = sourceIdToValue.get(node.nodeId);
      String childTenantId = childValue == null ? null : tenantValueToId.get(childValue);
      if (childTenantId == null) {
        skipped++;
        continue;
      }
      String parentTenantId = resolveParentTenantId(node, sourceIdToValue, tenantValueToId,
          childValue, clientId);
      inserted += insertTreeNode(treeId, childTenantId, clientId, parentTenantId, node.seqno);
    }
    if (log.isDebugEnabled()) {
      log.debug("Provisioned {} AD_TREENODE row(s) ({} source nodes not present in tenant) for"
          + " client {} EV tree {}", inserted, skipped, clientId, treeId);
    }
  }

  /**
   * Resolves the tenant {@code C_ELEMENTVALUE} id to use as a node's parent. Root nodes (and nodes
   * whose parent account cannot be resolved in this tenant) attach to the tree root
   * ({@code GENERIC_ORG_ID}); the unresolved case is logged so drift in the bundled data is visible.
   */
  private String resolveParentTenantId(SourceTreeNode node, Map<String, String> sourceIdToValue,
      Map<String, String> tenantValueToId, String childValue, String clientId) {
    if (GENERIC_ORG_ID.equals(node.parentId)) {
      return GENERIC_ORG_ID;
    }
    String parentValue = sourceIdToValue.get(node.parentId);
    String resolvedParent = parentValue == null ? null : tenantValueToId.get(parentValue);
    if (resolvedParent != null) {
      return resolvedParent;
    }
    log.warn("Parent account for node {} (parent value {}) not found for client {};"
        + " attaching to root", childValue, parentValue, clientId);
    return GENERIC_ORG_ID;
  }

  /** Runs one idempotent {@link #ELEMENT_TREENODE_SQL} insert; returns the rows created (0 or 1). */
  protected int insertTreeNode(String treeId, String nodeId, String clientId, String parentId,
      long seqno) {
    return OBDal.getInstance().getSession()
        .createNativeQuery(ELEMENT_TREENODE_SQL)
        .setParameter("treeId", treeId)
        .setParameter("nodeId", nodeId)
        .setParameter("clientId", clientId)
        .setParameter("parentId", parentId)
        .setParameter("seqno", seqno)
        .executeUpdate();
  }

  /** Maps the tenant's account {@code value} (account code) to its {@code C_ELEMENTVALUE} id. */
  protected Map<String, String> loadTenantElementValueIds(Client client) {
    OBCriteria<ElementValue> criteria = OBDal.getInstance().createCriteria(ElementValue.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(ElementValue.PROPERTY_CLIENT, client));
    Map<String, String> byValue = new HashMap<>();
    for (ElementValue elementValue : criteria.list()) {
      byValue.put(elementValue.getSearchKey(), elementValue.getId());
    }
    return byValue;
  }

  /**
   * Reads the bundled {@code AD_TREENODE.xml}, keeping only the rows of GOClient's chart-of-accounts
   * (EV) tree at generic-organization level. Returns {@code (sourceNodeId, sourceParentId, seqno)}
   * triples in document order.
   */
  protected List<SourceTreeNode> loadSourceTreeNodes() {
    List<SourceTreeNode> nodes = new ArrayList<>();
    Document document = parseSourceResource(SOURCE_TREENODE_RESOURCE);
    if (document == null) {
      return nodes;
    }
    NodeList rows = document.getElementsByTagName("AD_TREENODE");
    for (int i = 0; i < rows.getLength(); i++) {
      org.w3c.dom.Element row = (org.w3c.dom.Element) rows.item(i);
      if (!SOURCE_ELEMENT_TREE_ID.equals(textOf(row, "AD_TREE_ID"))
          || !GENERIC_ORG_ID.equals(textOf(row, "AD_ORG_ID"))) {
        continue;
      }
      String nodeId = textOf(row, "NODE_ID");
      if (nodeId != null) {
        String parentId = textOf(row, "PARENT_ID");
        nodes.add(new SourceTreeNode(nodeId, parentId == null ? GENERIC_ORG_ID : parentId,
            parseSeqno(textOf(row, "SEQNO"))));
      }
    }
    return nodes;
  }

  /** Maps the bundled source {@code C_ELEMENTVALUE} id to its {@code value} (account code). */
  protected Map<String, String> loadSourceElementValues() {
    Map<String, String> byId = new HashMap<>();
    Document document = parseSourceResource(SOURCE_ELEMENT_VALUE_RESOURCE);
    if (document == null) {
      return byId;
    }
    NodeList rows = document.getElementsByTagName("C_ELEMENTVALUE");
    for (int i = 0; i < rows.getLength(); i++) {
      org.w3c.dom.Element row = (org.w3c.dom.Element) rows.item(i);
      String id = textOf(row, "C_ELEMENTVALUE_ID");
      String value = textOf(row, "VALUE");
      if (id != null && value != null) {
        byId.put(id, value);
      }
    }
    return byId;
  }

  /** Parses a seqno cell to a long, defaulting to 0 when blank or non-numeric. */
  private long parseSeqno(String raw) {
    if (raw == null) {
      return 0L;
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  /** Returns the trimmed text of the first {@code tagName} child of {@code parent}, or null. */
  private String textOf(org.w3c.dom.Element parent, String tagName) {
    NodeList matches = parent.getElementsByTagName(tagName);
    if (matches.getLength() == 0 || matches.item(0).getTextContent() == null) {
      return null;
    }
    String text = matches.item(0).getTextContent().trim();
    return text.isEmpty() ? null : text;
  }

  /** Parses a bundled sourcedata XML resource from the classpath; returns null when absent. */
  private Document parseSourceResource(String resourcePath) {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    if (classLoader == null) {
      classLoader = OnboardingAccountingWiringService.class.getClassLoader();
    }
    try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
      if (inputStream == null) {
        return null;
      }
      return newSourceDocumentBuilder().parse(inputStream);
    } catch (Exception e) {
      throw new OBException("Failed to read bundled chart-of-accounts sourcedata " + resourcePath, e);
    }
  }

  /** Builds a hardened, non-validating XML parser for the bundled sourcedata. */
  private DocumentBuilder newSourceDocumentBuilder() {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder();
    } catch (Exception e) {
      throw new OBException("Failed to create a secure XML parser for chart-of-accounts sourcedata",
          e);
    }
  }

  /** Immutable source tree-node triple parsed from the bundled {@code AD_TREENODE.xml}. */
  protected static final class SourceTreeNode {
    private final String nodeId;
    private final String parentId;
    private final long seqno;

    SourceTreeNode(String nodeId, String parentId, long seqno) {
      this.nodeId = nodeId;
      this.parentId = parentId;
      this.seqno = seqno;
    }
  }

  /**
   * Re-brands the imported accounting schema and chart element(s) with the tenant's own client name.
   *
   * <p>The GOClient sample data ships these records with names hard-coded to the source template
   * client ("Esquema GO", "Arbol de cuentas GO", "GOClient Account"). Leaving them untouched would
   * brand every new tenant's chart with "GO"; instead the source moniker is replaced with the new
   * client's name in the schema name and the element name/description, matching the R1
   * chart-of-accounts data-fix. Only these two records carry the moniker — the account values
   * themselves are left intact.
   */
  protected void rebrandImportedChartNames(Client client, AcctSchema ledger) {
    String clientName = client.getName();
    ledger.setName(replaceSourceMoniker(ledger.getName(), clientName));
    OBDal.getInstance().save(ledger);

    OBCriteria<Element> criteria = OBDal.getInstance().createCriteria(Element.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Element.PROPERTY_CLIENT, client));
    for (Element element : criteria.list()) {
      element.setName(replaceSourceMoniker(element.getName(), clientName));
      element.setDescription(replaceSourceMoniker(element.getDescription(), clientName));
      OBDal.getInstance().save(element);
    }
  }

  /**
   * Replaces every {@link OnboardingSourceMoniker source-client moniker} in {@code value} with the
   * tenant's client name. Returns {@code value} unchanged when it is null or empty.
   */
  protected String replaceSourceMoniker(String value, String clientName) {
    return OnboardingSourceMoniker.replace(value, clientName);
  }

  /**
   * Provisions the per-entity posting accounts (Gap A2) for the tenant's business-partner groups,
   * product categories, customers, vendors and products.
   *
   * <p>Etendo's posting engine ({@code AcctServer}) does NOT fall back to {@code C_ACCTSCHEMA_DEFAULT}
   * to resolve the accounts of these entities: posting an invoice fails with
   * {@code Account Not Defined For …} (or {@code IllegalStateException} for the per-BP/product
   * lookups) unless dedicated rows exist in {@code C_BP_GROUP_ACCT}, {@code M_PRODUCT_CATEGORY_ACCT},
   * {@code C_BP_CUSTOMER_ACCT}, {@code C_BP_VENDOR_ACCT} and {@code M_PRODUCT_ACCT}. The dataset
   * import brings in the groups/categories/products but not these derived posting rows, so they are
   * created here, right after the ledger is wired.
   *
   * <p>This mirrors step 11 of the {@code R1-chart-of-accounts} corrective data-fix one-for-one
   * (same column lists, same {@code NOT EXISTS} guards, {@code ad_org_id} inherited from each source
   * entity, defaults copied from the single {@code C_ACCTSCHEMA_DEFAULT} row). It is set-based and
   * idempotent: each statement skips entities that already have a row for this schema, so re-running
   * onboarding never double-inserts. Entities created AFTER onboarding still need their own posting
   * rows at creation time (separate concern, not covered here).
   *
   * <p>NOTE: at this point in the onboarding chain the only business partners are those carried by
   * the dataset import (none — {@code C_BPARTNER} is not imported), so the customer/vendor inserts
   * are no-ops here. The default customer is created several steps later; its posting account is
   * provisioned by {@link #wireBusinessPartnerAccounts}, which the servlet calls after
   * {@code ensureDefaultCustomer}.
   *
   * @param client target client
   * @param ledger the accounting schema whose defaults are copied
   */
  protected void provisionEntityPostingAccounts(Client client, AcctSchema ledger) {
    String clientId = client.getId();
    String schemaId = ledger.getId();
    runEntityAcctInsert(BP_GROUP_ACCT_SQL, clientId, schemaId);
    runEntityAcctInsert(PRODUCT_CATEGORY_ACCT_SQL, clientId, schemaId);
    runEntityAcctInsert(BP_CUSTOMER_ACCT_SQL, clientId, schemaId);
    runEntityAcctInsert(BP_VENDOR_ACCT_SQL, clientId, schemaId);
    runEntityAcctInsert(PRODUCT_ACCT_SQL, clientId, schemaId);
    runEntityAcctInsert(TAX_ACCT_SQL, clientId, schemaId);
  }

  /** Runs one {@code INSERT … SELECT} posting-account statement and logs the rows created. */
  protected void runEntityAcctInsert(String sql, String clientId, String schemaId) {
    int rows = OBDal.getInstance().getSession()
        .createNativeQuery(sql)
        .setParameter("clientId", clientId)
        .setParameter("schemaId", schemaId)
        .executeUpdate();
    if (rows > 0 && log.isDebugEnabled()) {
      log.debug("Provisioned {} posting-account row(s) for client {}", rows, clientId);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // A2 posting-account INSERT … SELECT statements (kept in lockstep with R1-chart-of-accounts step
  // 11). Named params :clientId / :schemaId are bound as values; ad_org_id is inherited from each
  // source entity; PKs are minted with get_uuid(); NOT EXISTS makes each statement idempotent.
  // ---------------------------------------------------------------------------------------------

  private static final String BP_GROUP_ACCT_SQL =
      "INSERT INTO c_bp_group_acct ("
      + "  c_bp_group_acct_id, ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,"
      + "  c_bp_group_id, c_acctschema_id,"
      + "  c_receivable_acct, c_prepayment_acct,"
      + "  v_liability_acct, v_liability_services_acct, v_prepayment_acct,"
      + "  paydiscount_exp_acct, writeoff_acct, paydiscount_rev_acct, writeoff_rev_acct,"
      + "  notinvoicedreceivables_acct, notinvoicedrevenue_acct, notinvoicedreceipts_acct,"
      + "  unearnedrevenue_acct) "
      + "SELECT get_uuid(), :clientId, g.ad_org_id, 'Y', now(), '0', now(), '0',"
      + "  g.c_bp_group_id, :schemaId,"
      + "  d.c_receivable_acct, d.c_prepayment_acct,"
      + "  d.v_liability_acct, d.v_liability_services_acct, d.v_prepayment_acct,"
      + "  d.paydiscount_exp_acct, d.writeoff_acct, d.paydiscount_rev_acct, d.writeoff_rev_acct,"
      + "  d.notinvoicedreceivables_acct, d.notinvoicedrevenue_acct, d.notinvoicedreceipts_acct,"
      + "  d.unearnedrevenue_acct "
      + "FROM c_bp_group g, c_acctschema_default d "
      + "WHERE g.ad_client_id = :clientId AND d.c_acctschema_id = :schemaId "
      + "  AND NOT EXISTS (SELECT 1 FROM c_bp_group_acct a"
      + "    WHERE a.c_bp_group_id = g.c_bp_group_id AND a.c_acctschema_id = :schemaId)";

  private static final String PRODUCT_CATEGORY_ACCT_SQL =
      "INSERT INTO m_product_category_acct ("
      + "  m_product_category_acct_id, ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,"
      + "  m_product_category_id, c_acctschema_id,"
      + "  p_revenue_acct, p_expense_acct, p_cogs_acct, p_asset_acct,"
      + "  p_purchasepricevariance_acct, p_invoicepricevariance_acct,"
      + "  p_tradediscountrec_acct, p_tradediscountgrant_acct,"
      + "  p_revenue_return_acct, p_cogs_return_acct) "
      + "SELECT get_uuid(), :clientId, c.ad_org_id, 'Y', now(), '0', now(), '0',"
      + "  c.m_product_category_id, :schemaId,"
      + "  d.p_revenue_acct, d.p_expense_acct, d.p_cogs_acct, d.p_asset_acct,"
      + "  d.p_purchasepricevariance_acct, d.p_invoicepricevariance_acct,"
      + "  d.p_tradediscountrec_acct, d.p_tradediscountgrant_acct,"
      + "  d.p_revenue_return_acct, d.p_cogs_return_acct "
      + "FROM m_product_category c, c_acctschema_default d "
      + "WHERE c.ad_client_id = :clientId AND d.c_acctschema_id = :schemaId "
      + "  AND NOT EXISTS (SELECT 1 FROM m_product_category_acct a"
      + "    WHERE a.m_product_category_id = c.m_product_category_id AND a.c_acctschema_id = :schemaId)";

  private static final String BP_CUSTOMER_ACCT_SQL =
      "INSERT INTO c_bp_customer_acct ("
      + "  c_bp_customer_acct_id, ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,"
      + "  c_bpartner_id, c_acctschema_id, c_receivable_acct, c_prepayment_acct) "
      + "SELECT get_uuid(), :clientId, bp.ad_org_id, 'Y', now(), '0', now(), '0',"
      + "  bp.c_bpartner_id, :schemaId, d.c_receivable_acct, d.c_prepayment_acct "
      + "FROM c_bpartner bp, c_acctschema_default d "
      + "WHERE bp.ad_client_id = :clientId AND bp.iscustomer = 'Y' AND d.c_acctschema_id = :schemaId "
      + "  AND NOT EXISTS (SELECT 1 FROM c_bp_customer_acct a"
      + "    WHERE a.c_bpartner_id = bp.c_bpartner_id AND a.c_acctschema_id = :schemaId)";

  private static final String BP_VENDOR_ACCT_SQL =
      "INSERT INTO c_bp_vendor_acct ("
      + "  c_bp_vendor_acct_id, ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,"
      + "  c_bpartner_id, c_acctschema_id, v_liability_acct, v_prepayment_acct) "
      + "SELECT get_uuid(), :clientId, bp.ad_org_id, 'Y', now(), '0', now(), '0',"
      + "  bp.c_bpartner_id, :schemaId, d.v_liability_acct, d.v_prepayment_acct "
      + "FROM c_bpartner bp, c_acctschema_default d "
      + "WHERE bp.ad_client_id = :clientId AND bp.isvendor = 'Y' AND d.c_acctschema_id = :schemaId "
      + "  AND NOT EXISTS (SELECT 1 FROM c_bp_vendor_acct a"
      + "    WHERE a.c_bpartner_id = bp.c_bpartner_id AND a.c_acctschema_id = :schemaId)";

  private static final String PRODUCT_ACCT_SQL =
      "INSERT INTO m_product_acct ("
      + "  m_product_acct_id, ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,"
      + "  m_product_id, c_acctschema_id,"
      + "  p_revenue_acct, p_expense_acct, p_cogs_acct, p_asset_acct,"
      + "  p_purchasepricevariance_acct, p_invoicepricevariance_acct,"
      + "  p_tradediscountrec_acct, p_tradediscountgrant_acct,"
      + "  p_revenue_return_acct, p_cogs_return_acct) "
      + "SELECT get_uuid(), :clientId, p.ad_org_id, 'Y', now(), '0', now(), '0',"
      + "  p.m_product_id, :schemaId,"
      + "  d.p_revenue_acct, d.p_expense_acct, d.p_cogs_acct, d.p_asset_acct,"
      + "  d.p_purchasepricevariance_acct, d.p_invoicepricevariance_acct,"
      + "  d.p_tradediscountrec_acct, d.p_tradediscountgrant_acct,"
      + "  d.p_revenue_return_acct, d.p_cogs_return_acct "
      + "FROM m_product p, c_acctschema_default d "
      + "WHERE p.ad_client_id = :clientId AND d.c_acctschema_id = :schemaId "
      + "  AND NOT EXISTS (SELECT 1 FROM m_product_acct a"
      + "    WHERE a.m_product_id = p.m_product_id AND a.c_acctschema_id = :schemaId)";

  // Tax posting accounts: required for invoice posting (the tax-due/tax-credit lines).
  // Without a C_TAX_ACCT row per tax, AcctServer fails with "Account could not be found".
  // Taxes (C_TAX) are now system-level (ad_client_id = '0') and shared across tenants, so the
  // source side reads from the system catalog; the C_TAX_ACCT rows themselves are client-level
  // (ad_client_id = :clientId, ad_org_id '0' inherited from the system tax). Defaults are copied
  // from the tenant's single C_ACCTSCHEMA_DEFAULT row.
  private static final String TAX_ACCT_SQL =
      "INSERT INTO c_tax_acct ("
      + "  c_tax_acct_id, ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,"
      + "  c_tax_id, c_acctschema_id, t_due_acct, t_credit_acct) "
      + "SELECT get_uuid(), :clientId, t.ad_org_id, 'Y', now(), '0', now(), '0',"
      + "  t.c_tax_id, :schemaId, d.t_due_acct, d.t_credit_acct "
      + "FROM c_tax t, c_acctschema_default d "
      + "WHERE t.ad_client_id = '0' AND d.c_acctschema_id = :schemaId "
      + "  AND d.t_due_acct IS NOT NULL AND d.t_credit_acct IS NOT NULL "
      + "  AND NOT EXISTS (SELECT 1 FROM c_tax_acct a"
      + "    WHERE a.c_tax_id = t.c_tax_id AND a.c_acctschema_id = :schemaId)";

  protected Tree resolveTenantElementValueTree(Client client) {
    OBCriteria<Tree> criteria = OBDal.getInstance().createCriteria(Tree.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Tree.PROPERTY_CLIENT, client));
    criteria.add(Restrictions.eq(Tree.PROPERTY_TYPEAREA, ELEMENT_VALUE_TREE_TYPE));
    criteria.addOrderBy(Tree.PROPERTY_ID, true);
    criteria.setMaxResults(2);
    java.util.List<Tree> trees = criteria.list();
    if (trees.isEmpty()) {
      return null;
    }
    if (trees.size() > 1) {
      log.warn("Client {} has more than one EV tree; using {}", client.getId(), trees.get(0).getId());
    }
    return trees.get(0);
  }

  protected Client resolveClient(String clientId) {
    return OBDal.getInstance().get(Client.class, clientId);
  }

  protected Organization resolveOrganization(String orgId) {
    return OBDal.getInstance().get(Organization.class, orgId);
  }

  protected void flushChanges() {
    OBDal.getInstance().flush();
  }

  protected OBContext captureCurrentContext() {
    return OBContext.getOBContext();
  }

  protected void applyExecutionContext(String adminUserId, String adminRoleId,
      String clientId, String orgId) {
    OBContext.setOBContext(adminUserId, adminRoleId, clientId, orgId);
  }

  protected void restoreExecutionContext(OBContext previousContext) {
    OBContext.setOBContext(previousContext);
  }

  protected void enterAdminMode() {
    OBContext.setAdminMode(true);
  }

  protected void exitAdminMode() {
    OBContext.restorePreviousMode();
  }

  private void validateContext(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    if (clientId == null || clientId.isEmpty()) {
      throw new OBException("Missing client for accounting wiring");
    }
    if (orgId == null || orgId.isEmpty()) {
      throw new OBException("Missing organization for accounting wiring");
    }
    if (adminUserId == null || adminUserId.isEmpty()) {
      throw new OBException("Missing admin user for accounting wiring");
    }
    if (adminRoleId == null || adminRoleId.isEmpty()) {
      throw new OBException("Missing admin role for accounting wiring");
    }
  }
}
