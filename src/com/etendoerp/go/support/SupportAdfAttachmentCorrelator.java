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

package com.etendoerp.go.support;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SupportConversation;
import com.etendoerp.go.schemaforge.data.SupportMessage;

/**
 * Correlates Jira ADF (Atlassian Document Format) {@code media} node ids — and the raw {@code
 * attachments} JSON persisted on earlier {@link SupportMessage} rows — against a Jira issue's
 * REST attachment list ({@code {id, filename, mimeType, created, ...}}). Extracted from {@link
 * SupportJiraWebhookHandler} (SonarQube S1448 — too many methods) as a cohesive
 * "attachment-correlation" group: matching by direct id, falling back to closest-by-timestamp
 * pairing, and collecting the ADF media ids / already-linked attachment ids that feed that
 * matching. See {@link SupportJiraWebhookHandler#resolveCommentAttachments} for the caller that
 * ties this together with the wiki-markup (non-ADF) attachment path.
 */
final class SupportAdfAttachmentCorrelator {

  private static final String FIELD_ID = "id";
  private static final String FIELD_MIME_TYPE = "mimeType";

  /** Maximum gap allowed between a comment and an unclaimed attachment for the fallback,
   * closest-by-timestamp pairing to be trusted. {@link SupportJiraWebhookHandler#resolveCommentAttachments}'s
   * own javadoc already notes the realistic gap is "a couple of minutes" — a human agent attaches
   * a file in Jira's UI and Jira fires the resulting comment webhook within seconds to low minutes
   * of that. 15 minutes gives that a generous ~10x buffer for slower manual workflows or webhook
   * delivery lag, while still being tight enough to refuse a force-pair against a
   * same-day-but-hours-apart, unrelated attachment on a busy, long-lived ticket (the scenario a
   * 24-hour window let through). Beyond this window, "no match" is preferred over a wrong one. */
  private static final long MAX_FALLBACK_CORRELATION_DISTANCE_MILLIS = 15L * 60 * 1000; // 15 minutes

  private SupportAdfAttachmentCorrelator() {
    // Static helpers only.
  }

  /** Jira attachment ids already persisted on an earlier {@link SupportMessage} of the
   * conversation tied to {@code jiraKey} — read from that message's {@code attachments} JSON
   * column, the same shape {@link SupportJiraWebhookHandler#insertJiraMessage} writes. Used to
   * keep the fallback closest-by-timestamp correlation (see {@link #closestUnclaimedByTime}) from
   * re-pairing a later, unmatched media node with an attachment that a PREVIOUS webhook call (an
   * earlier comment on the same ticket) already legitimately linked to an earlier message: {@code
   * claimed} in {@link #correlateAttachments} only prevents double-matching within a single
   * webhook invocation, it has no memory of earlier invocations. */
  static Set<String> findAlreadyLinkedAttachmentIds(String jiraKey) {
    Set<String> ids = new HashSet<>();
    OBContext.setAdminMode(true);
    try {
      SupportConversation conv = SupportJiraWebhookHandler.findConversationByJiraKey(jiraKey);
      if (conv == null) return ids;
      OBCriteria<SupportMessage> crit = OBDal.getInstance().createCriteria(SupportMessage.class);
      crit.setFilterOnReadableClients(false);
      crit.setFilterOnReadableOrganization(false);
      crit.add(Restrictions.eq(SupportMessage.PROPERTY_CONVERSATION + ".id", conv.getId()));
      crit.add(Restrictions.isNotNull(SupportMessage.PROPERTY_ATTACHMENTS));
      for (SupportMessage msg : crit.list()) {
        collectAttachmentIds(msg.getAttachments(), ids);
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    return ids;
  }

  /** Parses a persisted {@code attachments} column ({@code [{id, filename, mimeType}]}) and adds
   * every {@code id} found into {@code ids}. Malformed JSON on a row is skipped rather than
   * failing the whole lookup. */
  static void collectAttachmentIds(String attachmentsJson, Set<String> ids) {
    if (attachmentsJson == null || attachmentsJson.isEmpty()) return;
    try {
      JSONArray arr = new JSONArray(attachmentsJson);
      for (int i = 0; i < arr.length(); i++) {
        JSONObject att = arr.optJSONObject(i);
        String id = att != null ? att.optString(FIELD_ID, "") : "";
        if (!id.isEmpty()) ids.add(id);
      }
    } catch (JSONException e) {
      // Malformed attachments JSON on this row — skip it, don't fail the whole lookup.
    }
  }

  /** Recursively collects the {@code attrs.id} of every ADF {@code media} node under {@code node}
   * (mirrors {@link SupportJiraWebhookHandler#extractAdfText}'s traversal/dual
   * String-or-JSONObject dispatch). */
  static void collectAdfMediaIds(Object node, List<String> ids) {
    if (node instanceof String) {
      collectAdfMediaIdsFromString((String) node, ids);
      return;
    }
    if (node instanceof JSONObject) {
      collectAdfMediaIdsFromObject((JSONObject) node, ids);
    }
  }

  private static void collectAdfMediaIdsFromString(String node, List<String> ids) {
    String s = node.trim();
    if (!s.startsWith("{")) return;
    try {
      collectAdfMediaIdsFromObject(new JSONObject(s), ids);
    } catch (Exception e) {
      // Not JSON after all — nothing to collect.
    }
  }

  private static void collectAdfMediaIdsFromObject(JSONObject obj, List<String> ids) {
    if ("media".equals(obj.optString("type", ""))) {
      JSONObject attrs = obj.optJSONObject("attrs");
      String id = attrs != null ? attrs.optString(FIELD_ID, "") : "";
      if (!id.isEmpty()) ids.add(id);
    }
    JSONArray content = obj.optJSONArray("content");
    if (content != null) {
      for (int i = 0; i < content.length(); i++) {
        Object child = content.opt(i);
        if (child instanceof JSONObject) collectAdfMediaIdsFromObject((JSONObject) child, ids);
      }
    }
  }

  /** Matches each ADF media id against {@code issueAttachments} by direct id equality first,
   * then pairs any leftovers with the closest-by-timestamp unclaimed attachment. Each match is
   * projected down to {@code {id, filename, mimeType}} — {@code id} is always the REST attachment
   * id (never the raw ADF media id), since that is what the content-proxy endpoint needs.
   * Equivalent to {@link #correlateAttachments(JSONArray, List, Date, Set)} with no cross-comment
   * exclusions — kept for callers/tests that only care about the within-call correlation. */
  static JSONArray correlateAttachments(JSONArray issueAttachments, List<String> mediaIds, Date commentTime) {
    return correlateAttachments(issueAttachments, mediaIds, commentTime, Collections.emptySet());
  }

  /** Same as {@link #correlateAttachments(JSONArray, List, Date)}, but the fallback
   * closest-by-timestamp pairing additionally skips any attachment whose id is in {@code
   * alreadyLinkedIds} — see {@link #findAlreadyLinkedAttachmentIds}. Direct id matches are not
   * filtered by this set: an exact ADF-media-id-to-attachment-id match is trusted regardless. */
  static JSONArray correlateAttachments(JSONArray issueAttachments, List<String> mediaIds, Date commentTime,
      Set<String> alreadyLinkedIds) {
    JSONArray result = new JSONArray();
    if (mediaIds.isEmpty() || issueAttachments.length() == 0) return result;
    Set<Integer> claimed = new HashSet<>();
    for (String mediaId : mediaIds) {
      int idx = indexOfAttachmentById(issueAttachments, mediaId, claimed);
      if (idx >= 0) {
        result.put(toAttachmentMeta(issueAttachments.optJSONObject(idx)));
        claimed.add(idx);
      }
    }
    int unmatched = mediaIds.size() - result.length();
    for (int n = 0; n < unmatched; n++) {
      int idx = closestUnclaimedByTime(issueAttachments, commentTime, claimed, alreadyLinkedIds);
      if (idx < 0) break;
      result.put(toAttachmentMeta(issueAttachments.optJSONObject(idx)));
      claimed.add(idx);
    }
    return result;
  }

  static int indexOfAttachmentById(JSONArray issueAttachments, String id, Set<Integer> claimed) {
    for (int i = 0; i < issueAttachments.length(); i++) {
      if (claimed.contains(i)) continue;
      JSONObject att = issueAttachments.optJSONObject(i);
      if (att != null && id.equals(att.optString(FIELD_ID, ""))) return i;
    }
    return -1;
  }

  static int closestUnclaimedByTime(JSONArray issueAttachments, Date commentTime, Set<Integer> claimed,
      Set<String> alreadyLinkedIds) {
    int bestIdx = -1;
    long bestDiff = Long.MAX_VALUE;
    for (int i = 0; i < issueAttachments.length(); i++) {
      if (claimed.contains(i)) continue;
      Long diff = candidateDiffMillis(issueAttachments.optJSONObject(i), commentTime, alreadyLinkedIds);
      if (diff != null && diff < bestDiff && diff <= MAX_FALLBACK_CORRELATION_DISTANCE_MILLIS) {
        bestDiff = diff;
        bestIdx = i;
      }
    }
    return bestIdx;
  }

  /** Absolute time gap (millis) between {@code commentTime} and {@code att}'s own {@code created}
   * timestamp, or {@code null} if {@code att} is missing, has no parseable timestamp, or is an
   * attachment already linked elsewhere ({@code alreadyLinkedIds}) — any of which disqualifies it
   * as a fallback candidate in {@link #closestUnclaimedByTime}. */
  private static Long candidateDiffMillis(JSONObject att, Date commentTime, Set<String> alreadyLinkedIds) {
    if (att == null) return null;
    String attId = att.optString(FIELD_ID, "");
    if (!attId.isEmpty() && alreadyLinkedIds.contains(attId)) return null;
    long attMillis = SupportJiraWebhookHandler.parseJiraInstantMillis(att.optString("created", ""));
    if (attMillis < 0) return null;
    return Math.abs(attMillis - commentTime.getTime());
  }

  static JSONObject toAttachmentMeta(JSONObject att) {
    try {
      return new JSONObject()
          .put(FIELD_ID, att.optString(FIELD_ID, ""))
          .put(SupportJiraWebhookHandler.FIELD_FILENAME, att.optString(SupportJiraWebhookHandler.FIELD_FILENAME, "attachment"))
          .put(FIELD_MIME_TYPE, att.optString(FIELD_MIME_TYPE, "application/octet-stream"));
    } catch (JSONException e) {
      // JSONObject#put only throws on a null key, which never happens here.
      throw new IllegalStateException(e);
    }
  }
}
