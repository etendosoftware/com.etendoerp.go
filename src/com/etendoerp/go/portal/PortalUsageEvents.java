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

import com.etendoerp.go.usageevents.UsageEvent;
import com.etendoerp.go.usageevents.UsageEventRecorder;
import com.etendoerp.go.usageevents.UsageEventTypes;

/**
 * The portal's KPI events (ETP-6209, plan §A.7).
 *
 * <p>Every event is keyed on the link's root ({@code sessionKey} = {@code ROOT_LINK_ID}), so "one
 * send" is one key whatever renewals followed it:
 * <ul>
 *   <li>open rate = distinct roots with a {@code portal.link.opened} / distinct roots with a
 *       {@code portal.link.sent};</li>
 *   <li>recurring access = Business Partners with {@code portal.link.opened} on two or more
 *       distinct roots.</li>
 * </ul>
 * {@link UsageEventRecorder#submit} never throws and never blocks, so recording cannot fail a send
 * or a portal request.
 */
final class PortalUsageEvents {

  private static final String ACTION_SEND = "send";
  private static final String ACTION_OPEN = "open";
  private static final String ACTION_RENEW = "renew";
  private static final String ACTION_DOWNLOAD = "download";

  private PortalUsageEvents() {
  }

  /** A link left with a document email. Recorded with the sender, from the send's context. */
  static void sent(PortalLink link, String sendLogId, int recipientCount) {
    UsageEventRecorder.submit(base(UsageEvent.builder().fromContext(), link)
        .eventType(UsageEventTypes.PORTAL_LINK_SENT)
        .action(ACTION_SEND)
        .outcome(UsageEvent.OUTCOME_OK)
        .property("contractName", link.getContractName())
        .property("sendLogId", sendLogId)
        .property("recipientCount", recipientCount)
        .build());
  }

  /** A link opened the portal (once per page load, on a link that is still valid). */
  static void opened(PortalLink link) {
    UsageEventRecorder.submit(base(UsageEvent.builder(), link)
        .eventType(UsageEventTypes.PORTAL_LINK_OPENED)
        .action(ACTION_OPEN)
        .outcome(UsageEvent.OUTCOME_OK)
        .property("origin", link.getOrigin())
        .property("firstUse", !link.isUsed())
        .build());
  }

  /** A renewal email left; {@code renewed} is the new link, {@code expired} the one opened. */
  static void renewed(PortalLink renewed) {
    UsageEventRecorder.submit(base(UsageEvent.builder(), renewed)
        .eventType(UsageEventTypes.PORTAL_LINK_RENEWED)
        .action(ACTION_RENEW)
        .outcome(UsageEvent.OUTCOME_OK)
        .property("expiredLinkId", renewed.getParentLinkId())
        .property("newLinkId", renewed.getId())
        .build());
  }

  /** A renewal was refused: blocked, inactive, plan expired, a limit, or the send failed. */
  static void renewalRefused(PortalLink expired, String errorCode) {
    UsageEventRecorder.submit(base(UsageEvent.builder(), expired)
        .eventType(UsageEventTypes.PORTAL_LINK_RENEWED)
        .action(ACTION_RENEW)
        .outcome(UsageEvent.OUTCOME_ERROR)
        .errorCode(errorCode)
        .property("expiredLinkId", expired.getId())
        .build());
  }

  /** A document's PDF was downloaded through the portal. */
  static void downloaded(PortalSession session, String documentId) {
    UsageEventRecorder.submit(UsageEvent.builder()
        .clientId(session.getClientId())
        .eventType(UsageEventTypes.PORTAL_DOCUMENT_DOWNLOADED)
        .sessionKey(session.getRootLinkId())
        .target(session.getEntryDocumentKind() == null ? null
            : session.getEntryDocumentKind().name())
        .action(ACTION_DOWNLOAD)
        .outcome(UsageEvent.OUTCOME_OK)
        .property("linkId", session.getLinkId())
        .property("documentId", documentId)
        .property("bpartnerId", session.getBusinessPartnerId())
        .property("isEntryDocument", documentId != null
            && documentId.equals(session.getEntryDocumentId()))
        .build());
  }

  private static UsageEvent.Builder base(UsageEvent.Builder builder, PortalLink link) {
    return builder
        .clientId(link.getClientId())
        .orgId(link.getOrganizationId())
        .sessionKey(link.getRootLinkId())
        .target(link.getDocumentKind() == null ? null : link.getDocumentKind().name())
        .property("linkId", link.getId())
        .property("documentId", link.getRecordId())
        .property("bpartnerId", link.getBusinessPartnerId());
  }
}
