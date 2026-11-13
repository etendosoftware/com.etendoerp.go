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

package com.etendoerp.go.portal;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.NeoPdfSourceConfig;
import com.etendoerp.go.schemaforge.NeoPrintAfterComplete;
import com.etendoerp.go.schemaforge.NeoPrintableRenderer;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * A portal document's printable when it has no stored one yet (ETP-6209, user decision 2026-11-13).
 *
 * <p><b>Not a seventh producer.</b> The PDF is rendered by the server-side render the MCP already
 * uses ({@link NeoPrintableRenderer}, run through {@link NeoPrintAfterComplete}'s worker): the
 * template the deployed frontend publishes, the document's own data builder, jsreport — the same
 * printable the app's preview and email produce. It is stored the way that render stores it: as the
 * record's main attachment ({@code markAsMain}), named {@code <spec>-<documentNo>.pdf} like the
 * app's send path, so the portal's later opens, the email download link and the app's preview reuse
 * it, and the app's freshness rule ({@code updatedAt} vs the record's {@code updated}) applies.</p>
 *
 * <p><b>Whose identity.</b> A portal request has no Etendo session, and the worker runs as a user,
 * a role, a client and an organization (it never falls back to an administrator). The portal uses:
 * the user who sent the document — the creator of the link's root row ({@code ETGO_PORTAL_LINK
 * .CREATEDBY} of the original send; a renewal is minted by the system and names no one) — with that
 * user's default role, or else their first active role, in the link's client; the client of the
 * link; and the document's own organization. The worker then applies the render's own guard: the
 * role must be able to write the document's attachments (D23), and a file a person marked main is
 * never replaced. No sender (system-created link, inactive user, no role in the client) means no
 * render.</p>
 *
 * <p><b>Cost.</b> Called only after {@code PortalDocumentQuery#findInScope} accepted the document
 * and only through the data rate limiter. Concurrent opens of one document join the render already
 * in flight in this JVM; across nodes the worker's advisory lock and re-check store one file. The
 * request waits at most the worker's bound.</p>
 *
 * <p><b>Never fails the customer.</b> Not configured (no jsreport / printables location), not
 * printable, not allowed, timed out, failed: {@code null}, and the caller answers as before (the
 * SPA shows the data sheet with "PDF no disponible").</p>
 */
final class PortalPrintable {

  private static final Logger log = LogManager.getLogger(PortalPrintable.class);
  private static final String DEFAULT_LANGUAGE = "es_ES";
  /** The system user ids a renewal or a background job leaves as creator: never a sender. */
  private static final List<String> SYSTEM_USERS = List.of("0", "100");

  private static final String ROOT_SENDER_SQL = "SELECT r.createdby FROM etgo_portal_link l"
      + " JOIN etgo_portal_link r ON r.etgo_portal_link_id = l.root_link_id"
      + " WHERE l.etgo_portal_link_id = :linkId";
  private static final String ROLE_SQL = "SELECT ur.ad_role_id FROM ad_user u"
      + " JOIN ad_user_roles ur ON ur.ad_user_id = u.ad_user_id AND ur.isactive = 'Y'"
      + " JOIN ad_role ro ON ro.ad_role_id = ur.ad_role_id AND ro.isactive = 'Y'"
      + " WHERE u.ad_user_id = :userId AND u.isactive = 'Y' AND ro.ad_client_id = :clientId"
      + " ORDER BY CASE WHEN ur.ad_role_id = u.default_ad_role_id THEN 0 ELSE 1 END, ur.created"
      + " LIMIT 1";

  private PortalPrintable() {
  }

  /**
   * Whether the kind's document can be rendered here: its window declares a printable and this
   * environment can render. The detail's {@code hasPdf} uses it for a document without a stored
   * PDF; the render itself may still decline it, and the page then falls back to the data sheet.
   */
  static boolean isRenderable(PortalDocumentKind kind) {
    return NeoPrintableRenderer.isConfigured() && printableEntity(kind) != null;
  }

  /**
   * Renders and stores the document's printable, then answers its main attachment; {@code null} when
   * nothing could be rendered or stored. Never throws.
   *
   * @param session  the validated session (its client, its root link, its language)
   * @param kind     the document's kind
   * @param document the document, already returned by {@code PortalDocumentQuery#findInScope}
   */
  static Attachment renderAndFind(PortalSession session, PortalDocumentKind kind,
      BaseOBObject document) {
    try {
      SFEntity entity = printableEntity(kind);
      if (entity == null || !NeoPrintableRenderer.isConfigured()) {
        return null;
      }
      String userId = senderOf(session);
      String roleId = userId == null ? null : roleOf(userId, session.getClientId());
      Object organization = document.get("organization");
      String orgId = organization instanceof Organization ? ((Organization) organization).getId()
          : null;
      if (roleId == null || orgId == null) {
        log.info("[portal] {}/{}: no PDF rendered, the link names no sender with a role",
            kind.name(), document.getId());
        return null;
      }
      String language = StringUtils.defaultIfBlank(session.getLanguage(), DEFAULT_LANGUAGE);
      NeoPrintableRenderer.Outcome outcome = NeoPrintAfterComplete.renderAndWaitAs(entity,
          (String) document.getId(), language, userId, roleId, session.getClientId(), orgId);
      if (outcome != NeoPrintableRenderer.Outcome.STORED
          && outcome != NeoPrintableRenderer.Outcome.ALREADY_CURRENT) {
        log.info("[portal] {}/{}: no PDF rendered ({})", kind.name(), document.getId(), outcome);
        return null;
      }
      // The worker committed the file in its own session; read it fresh.
      OBDal.getInstance().getSession().clear();
      return PortalDocumentsEndpoint.mainAttachment(session, kind, (String) document.getId());
    } catch (RuntimeException e) {
      log.warn("[portal] {}/{}: the PDF render failed: {}", kind.name(), document.getId(),
          e.getClass().getSimpleName());
      return null;
    }
  }

  /** The window entity declaring the kind's printable, or {@code null}. Admin mode by the caller. */
  static SFEntity printableEntity(PortalDocumentKind kind) {
    OBCriteria<SFSpec> specs = OBDal.getInstance().createCriteria(SFSpec.class);
    specs.add(Restrictions.eq(SFSpec.PROPERTY_NAME, kind.getSpecName()));
    specs.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    specs.setFilterOnReadableClients(false);
    specs.setFilterOnReadableOrganization(false);
    for (SFSpec spec : specs.list()) {
      OBCriteria<SFEntity> entities = OBDal.getInstance().createCriteria(SFEntity.class);
      entities.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", spec.getId()));
      entities.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
      entities.setFilterOnReadableClients(false);
      entities.setFilterOnReadableOrganization(false);
      for (SFEntity entity : entities.list()) {
        if (NeoPdfSourceConfig.printDocumentOf(entity) != null) {
          return entity;
        }
      }
    }
    return null;
  }

  /**
   * The user who sent the document: the creator of the link's root row, unless a system user. Also
   * the recipient of the quotation-decision notice (Part C). Admin mode by the caller.
   */
  static String senderOf(PortalSession session) {
    return senderOfLink(session.getLinkId());
  }

  /** As {@link #senderOf(PortalSession)}, from any link of the chain. */
  static String senderOfLink(String linkId) {
    List<?> rows = OBDal.getInstance().getSession().createNativeQuery(ROOT_SENDER_SQL)
        .setParameter("linkId", linkId).list();
    String userId = rows.isEmpty() ? null : StringUtils.trimToNull((String) rows.get(0));
    return userId == null || SYSTEM_USERS.contains(userId) ? null : userId;
  }

  /** The sender's default role, else their first active role, in the client; {@code null}. */
  private static String roleOf(String userId, String clientId) {
    List<?> rows = OBDal.getInstance().getSession().createNativeQuery(ROLE_SQL)
        .setParameter("userId", userId).setParameter("clientId", clientId).list();
    return rows.isEmpty() ? null : (String) rows.get(0);
  }
}
