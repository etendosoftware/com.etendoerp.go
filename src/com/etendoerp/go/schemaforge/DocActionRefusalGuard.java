package com.etendoerp.go.schemaforge;

import java.util.function.Supplier;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Refuses one {@code DocAction} button value, before the button's process runs, on the records a
 * customization says it must not run on (ETP-5704).
 *
 * <p><b>Why it exists.</b> The rule behind every caller is "the backend refuses what the UI does
 * not offer": REST single, REST batch and MCP all reach the entity customization's
 * {@code handle()} pre-hook, so a refusal here holds on every channel. Each caller states the
 * value it refuses and when; this class owns the part that must be identical for all of them —
 * deciding on the value that will actually run, and failing closed.</p>
 *
 * <p><b>Decides on the action that will actually run</b>,
 * {@link DocActionRequestSupport#effectiveDocAction}: the root {@code docAction} when the process
 * can write it on the record, otherwise the record's stored {@code DocAction}. So
 * {@code fieldValues.documentAction} vs {@code docAction}, an empty body running the stored value,
 * and {@code "cl"} / {@code " CL"} / garbage (which the DAL refuses to write, so the stored value
 * runs) are all judged as the procedure will run them.</p>
 *
 * <p><b>Fails closed.</b> A record id that does not exist answers 404 {@code Record not found}
 * ({@link NeoActionRecordGuard}'s status and message) whatever value is sent. A record, tab or
 * DocAction that cannot be resolved — so the action that will run is unknown — or a record check
 * that throws answers the caller's {@code checkFailed} response (a 500), and is logged.</p>
 *
 * <p><b>Pure structure.</b> It names no entity, no spec and no table; an entity gets a refusal
 * only by calling it from its own customization with its own value and condition.</p>
 */
final class DocActionRefusalGuard {

  private static final Logger log = LogManager.getLogger(DocActionRefusalGuard.class);

  /** 500 text when the action that will run, or the record's condition, cannot be read. */
  static final String MSG_CHECK_FAILED = "ETGO_DocActionCheckFailed";

  /** A condition that refuses the value on every record. */
  static final RecordCheck ALWAYS = recordId -> true;

  /**
   * Whether the refused value must be refused on one record. May throw: the guard then fails
   * closed.
   */
  @FunctionalInterface
  interface RecordCheck {
    /**
     * @param recordId the record the button runs on
     * @return {@code true} to refuse the request
     * @throws Exception when the condition cannot be read
     */
    boolean refuses(String recordId) throws Exception;
  }

  private DocActionRefusalGuard() {
  }

  /**
   * {@link #refuse(NeoContext, String, RecordCheck, Supplier, Supplier)} with translated bodies
   * ({@link NeoMessageText#error}): a {@code 400} with {@code refusalKey}, and a {@code 500} with
   * {@link #MSG_CHECK_FAILED}.
   *
   * @param context    the request reaching the customization's pre-hook
   * @param docAction  the button value refused, e.g. {@code CL}
   * @param refusedWhen on which records the value is refused
   * @param refusalKey the {@code AD_MESSAGE} value of the refusal
   * @return the error response, or {@code null} when the request may continue
   */
  static NeoResponse refuse(NeoContext context, String docAction, RecordCheck refusedWhen,
      String refusalKey) {
    return refuse(context, docAction, refusedWhen,
        () -> NeoMessageText.error(HttpServletResponse.SC_BAD_REQUEST, refusalKey),
        () -> NeoMessageText.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            MSG_CHECK_FAILED));
  }

  /**
   * The refusal of {@code docAction} on this record, or {@code null} when the request may
   * continue — it is not a POST of the DocAction button, it names no record, the value that will
   * run is another one, or {@code refusedWhen} lets it through.
   *
   * @param context     the request reaching the customization's pre-hook
   * @param docAction   the button value refused, e.g. {@code RE}
   * @param refusedWhen on which records the value is refused; evaluated only when that value
   *                    is the one that will run
   * @param refusal     the response for a refused request (a {@code 400})
   * @param checkFailed the response when the value or the condition cannot be read (a
   *                    {@code 500})
   * @return the error response, or {@code null}
   */
  static NeoResponse refuse(NeoContext context, String docAction, RecordCheck refusedWhen,
      Supplier<NeoResponse> refusal, Supplier<NeoResponse> checkFailed) {
    if (!DocActionRequestSupport.isDocActionButtonPost(context)) {
      return null;
    }
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return null;
    }
    try {
      if (!docAction.equals(DocActionRequestSupport.effectiveDocAction(context))
          || !refusedWhen.refuses(recordId)) {
        return null;
      }
    } catch (DocActionResolution.DocActionRecordNotFoundException e) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          NeoActionRecordGuard.MSG_RECORD_NOT_FOUND);
    } catch (Exception e) {
      log.error("DocAction on record {} refused: whether {} may run could not be read", recordId,
          docAction, e);
      return checkFailed.get();
    }
    log.info("DocAction {} on record {} refused", docAction, recordId);
    return refusal.get();
  }
}
