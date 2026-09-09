/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.function.Supplier;

import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Scoped write access to the {@code *} organization, for document-number generation only (ETP-5230).
 *
 * <p><b>Why this exists.</b> Every fixed GO role — and every per-user personal composition role — is
 * created with {@code AD_Role.UserLevel = "  O"} (see {@code SystemRoleTemplates#FIXED_ROLE_USER_LEVEL}),
 * and {@code OBContext#setWritableOrganizations} removes {@code "0"} from the writable-organization set
 * of any role at exactly that level. It does so <b>silently</b>, and regardless of the role actually
 * having {@code AD_Role_OrgAccess} to {@code *}. Meanwhile every document sequence the onboarding
 * dataset ships lives at org {@code *}. The APRM numbering path
 * ({@code FIN_Utility#getDocumentNo} → {@code Fin_UtilityLegacy#incrementSeqIfUpdateNext}) bumps the
 * counter through the DAL, so the write is security-checked and rejected with
 * "Organization 0 of object (ADSequence(…)) is not present in OrganizationList […]" — which made
 * reconciling, registering a payment and closing a cash drawer impossible for every invited user.
 * The tenant owner role ships {@code " CO"} and keeps {@code "0"}, which is the whole reason the same
 * flows work for them.
 *
 * <p><b>How to use it.</b> Wrap <i>only</i> the expression that produces the document number, never a
 * whole endpoint action:
 * <pre>
 *   String docNo = StarOrgWriteScope.withWritableStarOrg(
 *       () -&gt; FIN_Utility.getDocumentNo(docType, "FIN_Payment"));
 * </pre>
 *
 * <p><b>Why not {@code OBContext.setAdminMode(false)}.</b> It does not work here, and it fails
 * silently. {@code OBContext#doOrgClientAccessCheck} reads the <i>innermost</i> admin frame, and core
 * pushes its own {@code setAdminMode(true)} inside {@code APRM_MatchingUtility#addNewDraftReconciliation}
 * — so an outer frame is simply not the frame that gets consulted. The grant therefore has to change the
 * writable-organization <b>set</b>, which is what core's own {@code InitialOrgSetup} does for the same
 * kind of write.
 *
 * <p><b>Why the flush is inside the scope.</b> The security check fires on flush, not on save. At the
 * cash-close call site nothing flushes inside the enclosing method at all — the caller flushes much
 * later, outside this scope, which is precisely where it used to fail. Flushing here also writes out
 * whatever else the caller left pending; that is harmless, because inside the scope the writable set
 * only ever widens.
 *
 * <p><b>Never widen a scope to enclose a tenant-ownership check.</b>
 * {@link TenantOwnership#isVisibleToCurrentTenant} consults the readable-organization list, so a
 * {@code TenantOwnership.loadOwned} call executed inside this scope would have its guard transiently
 * relaxed for org {@code "0"}. Resolve every request-supplied id before entering.
 */
final class StarOrgWriteScope {

  /** {@code AD_Org_ID} of the {@code *} ("All Organizations") organization that every client shares. */
  private static final String STAR_ORG = "0";

  private StarOrgWriteScope() {
  }

  /**
   * Runs {@code body} with org {@code *} temporarily writable, flushes the resulting write while the
   * grant is still open, and restores the organization lists afterwards — including on exception.
   *
   * <p>A no-op wrapper when there is no session, or when the caller's role already holds {@code "0"}
   * (a client-admin {@code " CO"} role does): {@code body} then runs on the untouched context and
   * nothing is flushed, so those callers behave exactly as they did before this class existed.
   *
   * @param body the document-number generation to run — keep it as narrow as possible
   * @param <T>  the value {@code body} produces
   * @return the value produced by {@code body}
   */
  static <T> T withWritableStarOrg(Supplier<T> body) {
    OBContext context = OBContext.getOBContext();
    // Nothing to grant, and nothing to take away on the way out, in two cases: no session at all
    // (a background process), and a role that may already write at * — a client-admin " CO" role
    // does. No null-check on the set: OBContext#getWritableOrganizations always materializes one
    // (it returns a fresh HashSet), so a null there is not a state this can be in.
    if (context == null || context.getWritableOrganizations().contains(STAR_ORG)) {
      return body.get();
    }
    context.addWritableOrganization(STAR_ORG);
    try {
      T result = body.get();
      OBDal.getInstance().flush();
      return result;
    } finally {
      restoreOrgLists(context);
    }
  }

  /**
   * Restores the organization lists to exactly what the role alone implies.
   *
   * <p>{@code addWritableOrganization} is called here for its <b>side effect</b>: it nulls
   * {@code writableOrganizations} <i>and</i> {@code readableOrganizations}, so both are recomputed from
   * the role on the next read. {@code removeWritableOrganization} then drops {@code "0"} from
   * {@code additionalWritableOrganizations}, so that recompute yields the original sets.
   *
   * <p>Clearing the writable list is load-bearing: {@code removeWritableOrganization} alone only
   * touches the "additional" set, leaving an already-materialized {@code writableOrganizations} still
   * holding {@code "0"} for the rest of the request. Clearing the readable list is defensive — a role
   * carrying {@code *} in {@code AD_Role_OrgAccess} (which GO's personal roles do) already reads every
   * organization of its client, so for them this changes nothing; it matters only for a
   * hand-configured role without that access.
   *
   * <p>Do <b>not</b> append {@code removeFromWritableOrganization} here: {@code writableOrganizations}
   * is null at this point, and that method would dereference it.
   */
  private static void restoreOrgLists(OBContext context) {
    context.addWritableOrganization(STAR_ORG);
    context.removeWritableOrganization(STAR_ORG);
  }
}
