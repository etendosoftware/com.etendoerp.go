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
package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.util.Check;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.service.db.DbUtility;
import org.openbravo.service.json.DataToJsonConverter;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;
import org.openbravo.service.json.JsonToDataConverter;
import org.openbravo.service.json.JsonToDataConverter.JsonConversionError;
import org.openbravo.service.json.JsonUtils;

/**
 * A write path that persists exactly like core's but <b>does not end the transaction</b>, so a
 * caller that owns the transaction can make several writes atomic.
 *
 * <h2>Why this class exists (IMP-23)</h2>
 * <p>{@link BatchService} advertised {@code neo_batch} as atomic and was not. It does own the
 * transaction correctly — one {@code commitAndClose()} after the loop, {@code rollbackAndClose()}
 * on failure — but each operation reached
 * {@link DefaultJsonDataService#update(Map, String)}, whose success branch ends with
 * {@code OBDal.getInstance().commitAndClose()}. Every operation therefore committed itself, and
 * the batch's rollback had nothing left to undo: a failure at operation <i>n</i> left operations
 * {@code 0..n-1} durable. A benchmark run left an orphan {@code sales-order} header in the
 * database for five days exactly this way.</p>
 *
 * <p>There is no supported seam that suppresses that commit from the outside:
 * {@code SessionHandler.commitAndClose()} commits the real transaction unconditionally, DAL has no
 * nested-transaction or deferred-commit mode, and the one flag that <i>does</i> stop a commit
 * (disabling triggers) makes {@code commitAndClose} throw rather than skip. So the write path is
 * subclassed instead — which core explicitly sanctions: "This class can however also be extended
 * and instantiated directly."</p>
 *
 * <h2>What is different from core</h2>
 * <p>{@link #update(Map, String)} is core's method with exactly two lines removed:</p>
 * <ul>
 *   <li>the {@code commitAndClose()} that ends the success branch — the caller commits once, after
 *       every operation has succeeded;</li>
 *   <li>the {@code rollbackAndClose()} in the conversion-error branch — it would close the session
 *       out from under the caller mid-batch, so the caller is left to roll back instead.</li>
 * </ul>
 * <p>Everything else (save, flush, the dirty-session loop, the session clear, the refresh of
 * computed columns, {@code doPreAction}/{@code doPostAction}, the error shapes) is byte-for-byte
 * core behaviour. The naming follows the module's existing convention for
 * "deliberately leaves the commit to the caller" — see
 * {@code FiscalDeclCrudHandler#replaceIncidentsNoCommit} (ETP-4456).</p>
 *
 * <h2>The two things this class must keep in step with core</h2>
 * <p>Both are {@code private} upstream and so had to be duplicated. If core changes either, this
 * class drifts <b>silently</b> — there is no compiler error to catch it:</p>
 * <ul>
 *   <li>{@link #ADD_FLAG}, core's {@code "_doingAdd"} marker;</li>
 *   <li>{@link #getContentAsJSON(String)}.</li>
 * </ul>
 * <p>{@code SecureJsonDataService} in {@code com.smf.securewebservices} duplicates the same two
 * for the same reason, so this is the established cost of extending this write path.</p>
 *
 * <h2>What this does not fix</h2>
 * <p>A batch is still not hermetic. An operation whose handler runs an Etendo <i>process</i>
 * commits inside that process by design — {@code ProcessInvoiceUtil#process} is the known case —
 * and no caller-side transaction ownership can undo that. Atomicity here covers the ordinary
 * create/update write path, which is what {@code neo_batch} exposes.</p>
 *
 * <p>Whether a caller owns the transaction is tracked by
 * {@link BatchService#beginCallerOwnedTransaction()}, not here — see {@link #deferredCommitInstance()} for
 * why that flag must live on a class that does not extend core's service.</p>
 *
 * <p>This is a CDI bean on purpose. It cannot be {@code @Vetoed} and instantiated with
 * {@code new}, because the inherited {@code cachedPreference} and {@code extraActions} fields are
 * injected and {@code doPreAction} dereferences {@code extraActions}. Registering it as a bean is
 * safe for core's own lookup: {@code WeldUtils#getInstanceFromStaticBeanManager} keeps only beans
 * whose {@code getBeanClass()} is <i>exactly</i> the requested type, so this subclass is invisible
 * to {@code DefaultJsonDataService.getInstance()}.</p>
 *
 * @see BatchService#executeBatch(JSONArray)
 */
public class NeoBatchJsonDataService extends DefaultJsonDataService {

  private static final Logger log = LogManager.getLogger();

  /** Duplicated from core's private constant — see the class javadoc on silent drift. */
  private static final String ADD_FLAG = "_doingAdd";

  private static NeoBatchJsonDataService instance;

  /**
   * The deferred-commit write path, looked up lazily.
   *
   * <p>Not named {@code getInstance}: that name is already a public static method on core's
   * {@link DefaultJsonDataService}, and a package-private static of the same name would be an
   * illegal narrowing rather than an override.</p>
   *
   * <p>Lazily on purpose: core's {@link DefaultJsonDataService} resolves its own singleton from
   * Weld in a <i>static initializer</i>, so loading this class at all requires a running container.
   * Keeping the lookup here — and the "is the transaction caller-owned?" flag on
   * {@link BatchService}, which has no such superclass — means a caller can ask that question
   * without dragging Weld in, and this class is only touched when a write actually happens.</p>
   *
   * @return the shared instance
   */
  static synchronized NeoBatchJsonDataService deferredCommitInstance() {
    if (instance == null) {
      instance = WeldUtils.getInstanceFromStaticBeanManager(NeoBatchJsonDataService.class);
    }
    return instance;
  }

  /**
   * Core's {@code update} without the two calls that end the transaction. See the class javadoc
   * for why the body is duplicated rather than delegated to {@code super}.
   *
   * <p>{@code add} is not overridden: core's {@code add} sets {@link #ADD_FLAG} and delegates to
   * {@code update}, and that dispatches virtually to this method.</p>
   *
   * {@inheritDoc}
   */
  @Override
  public String update(Map<String, String> parameters, String content) {
    OBContext.setCrossOrgReferenceAdminMode();
    try {
      final boolean sendOriginalIdBack = "true"
          .equals(parameters.get(JsonConstants.SEND_ORIGINAL_ID_BACK));

      final JsonToDataConverter fromJsonConverter = OBProvider.getInstance()
          .get(JsonToDataConverter.class);

      String localContent;
      if (parameters.containsKey(ADD_FLAG)) {
        localContent = doPreAction(parameters, content, DataSourceAction.ADD);
      } else {
        localContent = doPreAction(parameters, content, DataSourceAction.UPDATE);
      }

      final Object jsonContent = getContentAsJSON(localContent);
      final List<JSONObject> originalData = new ArrayList<>();
      final List<BaseOBObject> bobs = buildBobsFromContent(parameters, fromJsonConverter,
          jsonContent, originalData);

      if (fromJsonConverter.hasErrors()) {
        // Core rolls back and closes the session here. Deliberately not done: the caller owns the
        // transaction, and closing it mid-batch would break every later operation. The caller
        // rolls back when it sees this validation_error.
        return conversionErrorResponse(fromJsonConverter);
      }
      return persistWithoutCommitting(parameters, content, bobs, originalData, sendOriginalIdBack);
    } catch (Throwable t) { // NOSONAR — core catches Throwable here and the shape must match
      Throwable localThrowable = DbUtility.getUnderlyingSQLException(t);
      logUpdateFailure(parameters, localThrowable);
      return JsonUtils.convertExceptionToJson(localThrowable);
    } finally {
      OBContext.restorePreviousCrossOrgReferenceMode();
    }
  }

  /**
   * Converts the request content into the list of {@link BaseOBObject}s to persist, and populates
   * {@code originalData} with each request's raw JSON object (needed later by
   * {@code sendOriginalIdBack}). Extracted from {@link #update(Map, String)} purely to reduce its
   * cognitive complexity (Sonar) — the branching/looping shape and behavior are unchanged from
   * core's original inline version.
   *
   * @param parameters        the datasource parameters
   * @param fromJsonConverter the converter used to build the {@link BaseOBObject}s
   * @param jsonContent       the {@code data} element, as a {@link JSONObject} or {@link JSONArray}
   * @param originalData      mutated in place with each request's raw JSON object
   * @return the objects built from the request
   */
  private List<BaseOBObject> buildBobsFromContent(Map<String, String> parameters,
      JsonToDataConverter fromJsonConverter, Object jsonContent, List<JSONObject> originalData)
      throws Exception {
    final List<BaseOBObject> bobs;
    if (jsonContent instanceof JSONArray) {
      bobs = fromJsonConverter.toBaseOBObjects((JSONArray) jsonContent);
      final JSONArray jsonArray = (JSONArray) jsonContent;
      for (int i = 0; i < jsonArray.length(); i++) {
        originalData.add(jsonArray.getJSONObject(i));
      }
    } else {
      final JSONObject jsonObject = (JSONObject) jsonContent;
      originalData.add(jsonObject);
      // now set the id and entityname from the parameters if it was set
      if (!jsonObject.has(JsonConstants.ID) && parameters.containsKey(JsonConstants.ID)) {
        jsonObject.put(JsonConstants.ID, parameters.containsKey(JsonConstants.ID));
      }
      if (!jsonObject.has(JsonConstants.ENTITYNAME)
          && parameters.containsKey(JsonConstants.ENTITYNAME)) {
        jsonObject.put(JsonConstants.ENTITYNAME, parameters.get(JsonConstants.ENTITYNAME));
      }
      bobs = Collections.singletonList(fromJsonConverter.toBaseOBObject(jsonObject));
    }
    return bobs;
  }

  /**
   * Logs an {@code update}/{@code add} failure exactly like core, minus the rollback core performs
   * before logging (the caller owns the transaction here). Extracted from {@link #update(Map,
   * String)} purely to reduce its cognitive complexity (Sonar).
   *
   * @param parameters      the datasource parameters, used to tell add from update for the message
   * @param localThrowable  the underlying throwable, already unwrapped via
   *                        {@link DbUtility#getUnderlyingSQLException(Throwable)}
   */
  private void logUpdateFailure(Map<String, String> parameters, Throwable localThrowable) {
    if (!(localThrowable instanceof OBException
        && !((OBException) localThrowable).isLogExceptionNeeded())) {
      if (parameters.containsKey(ADD_FLAG)) {
        log.error("Error adding new object (caller-owned transaction)", localThrowable);
      } else {
        log.error("Error updating object (caller-owned transaction)", localThrowable);
      }
    }
  }

  /**
   * Core's validation-error body, minus the rollback that produced it upstream.
   *
   * @param fromJsonConverter the converter holding the conversion errors
   * @return the {@code validation_error} response body
   */
  private String conversionErrorResponse(JsonToDataConverter fromJsonConverter)
      throws JSONException {
    final JSONObject jsonResult = new JSONObject();
    final JSONObject jsonResponse = new JSONObject();
    jsonResponse.put(JsonConstants.RESPONSE_STATUS,
        JsonConstants.RPCREQUEST_STATUS_VALIDATION_ERROR);
    final JSONObject errorsObject = new JSONObject();
    for (JsonConversionError error : fromJsonConverter.getErrors()) {
      errorsObject.put(error.getProperty().getName(), error.getThrowable().getMessage());
    }
    jsonResponse.put(JsonConstants.RESPONSE_ERRORS, errorsObject);
    jsonResult.put(JsonConstants.RESPONSE_RESPONSE, jsonResponse);
    return jsonResult.toString();
  }

  /**
   * Core's success branch verbatim, up to but not including {@code commitAndClose()}. The rows are
   * flushed — so they are visible to later operations in the same transaction, and to the database
   * constraints that must reject them now rather than at commit time — but nothing is committed.
   *
   * @param parameters         the datasource parameters
   * @param content            the original request content, passed through to {@code doPostAction}
   * @param bobs               the objects built from the request
   * @param originalData       the request's JSON objects, for {@code sendOriginalIdBack}
   * @param sendOriginalIdBack whether to echo the client-supplied ids back
   * @return the success response body
   */
  private String persistWithoutCommitting(Map<String, String> parameters, String content,
      List<BaseOBObject> bobs, List<JSONObject> originalData, boolean sendOriginalIdBack)
      throws JSONException {
    for (BaseOBObject bob : bobs) {
      OBDal.getInstance().save(bob);
    }
    OBDal.getInstance().flush();

    flushSessionUntilClean(content);

    // Objects might have been modified in DB through triggers, let's force them to be fetched from
    // DB again, to do so session is cleared (any possible modification is already persisted by the
    // previous flush). Clearing is safe mid-transaction: the inserts of earlier operations have
    // already been flushed to the database, so clearing only detaches the in-memory copies.
    OBDal.getInstance().getSession().clear();

    final List<BaseOBObject> refreshedBobs = refreshBobsFromDb(bobs);

    // almost successful, now create the response
    // needs to be done before the close of the session
    final DataToJsonConverter toJsonConverter = OBProvider.getInstance()
        .get(DataToJsonConverter.class);
    toJsonConverter.setAdditionalProperties(JsonUtils.getAdditionalProperties(parameters));
    final List<JSONObject> jsonObjects = toJsonConverter.toJsonObjects(refreshedBobs);

    if (sendOriginalIdBack) {
      attachOriginalIds(jsonObjects, originalData);
    }

    final JSONObject jsonResult = new JSONObject();
    final JSONObject jsonResponse = new JSONObject();
    jsonResponse.put(JsonConstants.RESPONSE_STATUS, JsonConstants.RPCREQUEST_STATUS_SUCCESS);
    jsonResponse.put(JsonConstants.RESPONSE_DATA, new JSONArray(jsonObjects));
    jsonResult.put(JsonConstants.RESPONSE_RESPONSE, jsonResponse);

    final String result;
    if (parameters.containsKey(ADD_FLAG)) {
      result = doPostAction(parameters, jsonResult.toString(), DataSourceAction.ADD, content);
    } else {
      result = doPostAction(parameters, jsonResult.toString(), DataSourceAction.UPDATE, content);
    }
    // Core calls OBDal.getInstance().commitAndClose() here. That single omission is the whole
    // point of this class: the caller commits once, after every operation has succeeded.
    return result;
  }

  /**
   * Core's dirty-session flush loop, verbatim. Extracted from {@link #persistWithoutCommitting}
   * purely to reduce its cognitive complexity (Sonar).
   *
   * @param content the original request content, used only for the giving-up error message
   */
  private void flushSessionUntilClean(String content) {
    // business event handlers can change the data
    // flush again before refreshing, refreshing can
    // potentially remove any in-memory changes
    int countFlushes = 0;
    while (OBDal.getInstance().isSessionDirty()) {
      OBDal.getInstance().flush();
      countFlushes++;
      // arbitrary point to give up...
      if (countFlushes > 100) {
        throw new OBException("Infinite loop in flushing when persisting json: " + content);
      }
    }
  }

  /**
   * Core's post-clear refresh loop, verbatim. Extracted from {@link #persistWithoutCommitting}
   * purely to reduce its cognitive complexity (Sonar).
   *
   * @param bobs the objects to re-fetch from the database
   * @return the refreshed objects, in the same order as {@code bobs}
   */
  private List<BaseOBObject> refreshBobsFromDb(List<BaseOBObject> bobs) {
    final List<BaseOBObject> refreshedBobs = new ArrayList<>();
    for (BaseOBObject bob : bobs) {
      // forcing fetch from DB
      BaseOBObject refreshedBob = OBDal.getInstance().get(bob.getEntityName(), bob.getId());

      // if object has computed columns refresh from the database too
      if (refreshedBob.getEntity().hasComputedColumns()) {
        OBDal.getInstance()
            .getSession()
            .refresh(refreshedBob.get(Entity.COMPUTED_COLUMNS_PROXY_PROPERTY));
      }
      refreshedBobs.add(refreshedBob);
    }
    return refreshedBobs;
  }

  /**
   * Core's {@code sendOriginalIdBack} logic, verbatim. Extracted from
   * {@link #persistWithoutCommitting} purely to reduce its cognitive complexity (Sonar).
   *
   * @param jsonObjects  the response objects, mutated in place with each original id
   * @param originalData the request's original JSON objects, in the same order as
   *                     {@code jsonObjects}
   */
  private void attachOriginalIds(List<JSONObject> jsonObjects, List<JSONObject> originalData)
      throws JSONException {
    // now it is assumed that the jsonObjects are the same size and the same location in the array
    if (jsonObjects.size() != originalData.size()) {
      throw new OBException("Unequal sizes in json data processed " + jsonObjects.size() + " "
          + originalData.size());
    }
    // now add the old id back
    for (int i = 0; i < originalData.size(); i++) {
      final JSONObject original = originalData.get(i);
      final JSONObject ret = jsonObjects.get(i);
      if (original.has(JsonConstants.ID) && original.has(JsonConstants.NEW_INDICATOR)) {
        ret.put(JsonConstants.ORIGINAL_ID, original.get(JsonConstants.ID));
      }
    }
  }

  /**
   * Duplicated from core's private method — see the class javadoc on silent drift.
   *
   * @param content the raw request content
   * @return the {@code data} element, as a {@link JSONObject} or {@link JSONArray}
   */
  private Object getContentAsJSON(String content) throws JSONException {
    Check.isNotNull(content, "Content must be set");
    final Object jsonRepresentation;
    if (content.trim().startsWith("[")) {
      jsonRepresentation = new JSONArray(content);
    } else {
      final JSONObject jsonObject = new JSONObject(content);
      jsonRepresentation = jsonObject.get(JsonConstants.DATA);
    }
    return jsonRepresentation;
  }
}
