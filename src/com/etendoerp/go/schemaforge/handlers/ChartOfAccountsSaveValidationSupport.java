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

package com.etendoerp.go.schemaforge.handlers;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * The "E — PGC save validation" behaviour of {@link ChartOfAccountsHandler} (see that class's
 * javadoc), split out purely to keep {@code ChartOfAccountsHandler}'s own method count under
 * the Sonar {@code java:S1448} limit — the same reason {@link ChartOfAccountsTreeMath} exists,
 * see that class's javadoc for the sibling precedent. Unlike that one this group is NOT
 * side-effect-free (every non-static method here touches {@code OBDal}), so it follows
 * {@link GlItemProvisioningSupport}'s instance-based shape instead of a static utility class.
 *
 * <p>Entry point is {@link #validateSave}, called once from {@code ChartOfAccountsHandler}'s
 * {@code handleCrudRequest} pre-hook. Validates the account code in a create or update request:
 * <ol>
 *   <li>If {@code searchKey} is present in the request body it must match exactly
 *       {@value #ACCOUNT_CODE_LENGTH} decimal digits — see {@link #isValidAccountCode}.</li>
 *   <li>Protected parent-like subaccount codes ending in {@code 0000} are rejected on create
 *       and on update, even when the request omits {@code searchKey} — see
 *       {@link #isProtectedParentLikeSubaccount}.</li>
 *   <li>On create: a {@code searchKey} already used by another account in the same client is
 *       rejected (ETP-5101) — without this the request falls through to the DB unique
 *       constraint and the user sees a raw/generic error.</li>
 *   <li>For updates (PUT/PATCH): if the account has children in {@code AD_TreeNode} and the
 *       code is being changed, the update is rejected (see {@link #applyImmutabilityRules}).</li>
 *   <li>For updates to leaf accounts (no children): if the first
 *       {@code ChartOfAccountsHandler.PGC_PREFIX_LENGTH} digits of the code would change, the
 *       update is rejected.</li>
 * </ol>
 *
 * <p>Returns {@code null} (fall through to default CRUD) when all validations pass or when
 * {@code searchKey} is absent from the body.
 */
class ChartOfAccountsSaveValidationSupport {

  private static final Logger log = LogManager.getLogger(ChartOfAccountsSaveValidationSupport.class);

  /** Required exact length of the account code. */
  static final int ACCOUNT_CODE_LENGTH = 8;

  static final String ERR_INVALID_CODE =
      "El código de cuenta debe tener exactamente 8 dígitos";

  static final String ERR_SUMMARY_LOCKED =
      "Las cuentas resumen no pueden modificarse";

  static final String ERR_PREFIX_LOCKED =
      "El prefijo PGC (primeros 4 dígitos) no puede modificarse";

  static final String ERR_PROTECTED_PARENT_LIKE_SUBACCOUNT =
      "Las subcuentas padre terminadas en 0000 no pueden crearse ni modificarse";

  /**
   * ETP-5101. Deliberately English, unlike its siblings above (which are hardcoded Spanish
   * and, as far as this handler is concerned, never routed through the frontend's
   * {@code backendErrors.js} translation map). This one IS registered there (see
   * {@code BACKEND_ERROR_MAP['backendError.accountAlreadyExists']}) so it renders correctly
   * in both locales — the pattern the siblings should have used too, tracked separately, not
   * fixed here. {@code %s} is the submitted 8-digit code.
   */
  static final String ERR_DUPLICATE_CODE = "Account %s already exists.";

  /**
   * SQL that returns the {@code AD_Tree_ID} for a given {@code C_ElementValue_ID}.
   * Used to scope the children-count query to the correct chart of accounts tree.
   */
  private static final String SQL_TREE_ID =
      "SELECT AD_Tree_ID FROM AD_TreeNode WHERE Node_ID = :nodeId LIMIT 1";

  /**
   * SQL that counts immediate children of a node in a specific tree.
   * If count > 0 the account is a parent/summary account.
   */
  private static final String SQL_CHILDREN_COUNT =
      "SELECT COUNT(*) FROM AD_TreeNode "
      + "WHERE Parent_ID = :parentId AND AD_Tree_ID = :treeId";

  NeoResponse validateSave(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }

    boolean isNewRecord = "POST".equals(context.getHttpMethod())
        || context.getRecordId() == null;
    String submittedCode = body.optString(ChartOfAccountsHandler.FIELD_SEARCH_KEY, null);
    if (submittedCode == null) {
      return isNewRecord ? null : validateExistingProtectedAccount(context.getRecordId());
    }

    // Validation 1: exactly 8 decimal digits
    if (!isValidAccountCode(submittedCode)) {
      return NeoResponse.error(400, ERR_INVALID_CODE);
    }

    if (isProtectedParentLikeSubaccount(submittedCode)) {
      return NeoResponse.error(400, ERR_PROTECTED_PARENT_LIKE_SUBACCOUNT);
    }

    // New records: format/protected-code checks apply, plus a duplicate-code check
    if (isNewRecord) {
      return validateNewRecordCode(submittedCode, context.getObContext());
    }

    // Update: apply immutability rules
    OBContext.setAdminMode(true);
    try {
      return applyImmutabilityRules(context.getRecordId(), submittedCode);
    } catch (Exception e) {
      log.error("ChartOfAccountsSaveValidationSupport.validateSave error for recordId={}: {}",
          context.getRecordId(), e.getMessage(), e);
      return null; // let the default handler proceed
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** ETP-5101 — rejects a create whose {@code searchKey} is already used in this client. */
  private NeoResponse validateNewRecordCode(String submittedCode, OBContext obCtx) {
    OBContext.setAdminMode(true);
    try {
      if (findDuplicateSearchKey(submittedCode, obCtx) != null) {
        return NeoResponse.error(409, String.format(ERR_DUPLICATE_CODE, submittedCode));
      }
      return null;
    } catch (Exception e) {
      log.error("ChartOfAccountsSaveValidationSupport.validateSave duplicate-code check "
          + "failed for searchKey={}: {}", submittedCode, e.getMessage(), e);
      return null; // let the default handler proceed
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Looks up an existing {@code ElementValue} with the same {@code searchKey} in the request's
   * client, if any. Never creates anything — read-only lookup, caller decides what to do with
   * the result.
   *
   * @param searchKey the submitted 8-digit code
   * @param obCtx     the request's {@link OBContext}; a {@code null} client means the lookup
   *                  can't be scoped, so no duplicate is reported
   * @return the existing account with that code, or {@code null} if none / no client
   */
  private ElementValue findDuplicateSearchKey(String searchKey, OBContext obCtx) {
    if (obCtx == null || obCtx.getCurrentClient() == null) {
      return null;
    }
    OBCriteria<ElementValue> criteria = OBDal.getInstance().createCriteria(ElementValue.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(ElementValue.PROPERTY_CLIENT, obCtx.getCurrentClient()));
    criteria.add(Restrictions.eq(ElementValue.PROPERTY_SEARCHKEY, searchKey));
    criteria.setMaxResults(1);
    return (ElementValue) criteria.uniqueResult();
  }

  /**
   * Applies the two immutability rules for an existing account:
   * summary-account code lock and leaf-account PGC prefix lock.
   *
   * @param recordId      the {@code C_ElementValue_ID} being updated
   * @param submittedCode the new {@code Value} submitted by the client
   * @return an error {@link NeoResponse} if a rule is violated, {@code null} otherwise
   */
  private NeoResponse applyImmutabilityRules(String recordId, String submittedCode) {
    ElementValue existing = OBDal.getInstance().get(ElementValue.class, recordId);
    if (existing == null) {
      return null; // record not found — let the default handler return 404
    }

    String currentCode = existing.getSearchKey();
    if (currentCode == null) {
      return null; // no current code to compare
    }

    if (isProtectedParentLikeSubaccount(currentCode)) {
      return NeoResponse.error(400, ERR_PROTECTED_PARENT_LIKE_SUBACCOUNT);
    }

    boolean codeChanged = !submittedCode.equals(currentCode);
    int childrenCount = countChildren(recordId);
    boolean hasChildren = childrenCount > 0;

    // Rule 2: summary account (has children) — code must not change
    if (hasChildren && codeChanged) {
      return NeoResponse.error(400, ERR_SUMMARY_LOCKED);
    }

    // Rule 3: leaf account (no children) — PGC prefix (first 4 digits) is immutable
    int prefixLength = ChartOfAccountsHandler.PGC_PREFIX_LENGTH;
    if (!hasChildren && codeChanged
        && currentCode.length() >= prefixLength
        && submittedCode.length() >= prefixLength
        && !submittedCode.substring(0, prefixLength)
            .equals(currentCode.substring(0, prefixLength))) {
      return NeoResponse.error(400, ERR_PREFIX_LOCKED);
    }

    return null;
  }

  private NeoResponse validateExistingProtectedAccount(String recordId) {
    OBContext.setAdminMode(true);
    try {
      ElementValue existing = OBDal.getInstance().get(ElementValue.class, recordId);
      if (existing != null && isProtectedParentLikeSubaccount(existing.getSearchKey())) {
        return NeoResponse.error(400, ERR_PROTECTED_PARENT_LIKE_SUBACCOUNT);
      }
      return null;
    } catch (Exception e) {
      log.error("ChartOfAccountsSaveValidationSupport.validateExistingProtectedAccount error "
          + "for recordId={}: {}", recordId, e.getMessage(), e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  static boolean isValidAccountCode(String code) {
    return code != null && code.matches("\\d{" + ACCOUNT_CODE_LENGTH + "}");
  }

  static boolean isProtectedParentLikeSubaccount(String code) {
    return isValidAccountCode(code) && code.endsWith("0000");
  }

  /**
   * Counts the number of immediate children of {@code parentId} in {@code AD_TreeNode}.
   * Scopes the query to the tree that contains the node (first match).
   *
   * @param parentId a {@code C_ElementValue_ID}
   * @return the number of child nodes; 0 if the node is not in any tree
   */
  @SuppressWarnings("unchecked")
  int countChildren(String parentId) {
    NativeQuery<Object> treeIdQry = (NativeQuery<Object>) OBDal.getInstance()
        .getSession()
        .createNativeQuery(SQL_TREE_ID);
    treeIdQry.setParameter("nodeId", parentId);
    List<Object> treeIdRows = treeIdQry.list();

    if (treeIdRows.isEmpty()) {
      return 0;
    }
    String treeId = String.valueOf(treeIdRows.get(0));

    NativeQuery<Object> countQry = (NativeQuery<Object>) OBDal.getInstance()
        .getSession()
        .createNativeQuery(SQL_CHILDREN_COUNT);
    countQry.setParameter("parentId", parentId);
    countQry.setParameter("treeId", treeId);
    List<Object> countRows = countQry.list();

    if (countRows.isEmpty()) {
      return 0;
    }
    Object countVal = countRows.get(0);
    if (countVal instanceof Number) {
      return ((Number) countVal).intValue();
    }
    return 0;
  }
}
