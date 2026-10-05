package com.etendoerp.go.schemaforge;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.weld.WeldUtils;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.auth.EnvironmentAuthOutcome;
import com.etendoerp.go.auth.EnvironmentRequestAuthenticator;
import com.etendoerp.go.auth.SurfacePolicy;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAuditTokenRefresh;
import com.etendoerp.go.schemaforge.util.NeoHandlerResolutionCache;

/**
 * Shared lookups used by {@link NeoServlet}.
 */
class NeoServletSupport {

  private static final Logger log = LogManager.getLogger(NeoServletSupport.class);
  private static final EnvironmentRequestAuthenticator AUTHENTICATOR =
      new EnvironmentRequestAuthenticator();

  private NeoServletSupport() {
  }

  /**
   * Authenticates a request to one of the environment-scoped servlets that are not
   * {@link NeoServlet} itself (report selectors, survey configuration) through the shared
   * pipeline (ETP-5455). It used to be a bearer-only JWT decode: those servlets answered 401 to
   * the cookie session the SPA actually sends, ignored the legacy kill switch, and never checked
   * commercial access.
   *
   * @param request the incoming request
   * @param policy  what the calling surface requires beyond a valid credential
   * @return the outcome; on refusal it carries the status and message to answer with
   */
  static EnvironmentAuthOutcome authenticate(HttpServletRequest request, SurfacePolicy policy) {
    return AUTHENTICATOR.authenticate(request, policy);
  }

  /**
   * Dispatch to a CDI-discovered {@link NeoHandler} by {@code Java_Qualifier}, running the
   * default CRUD service as either the sole result (no handler / handler declines via a
   * {@code null} pre-hook) or as the wrapped "default service" step between the handler's
   * pre- and post-hooks. Mirrors {@link NeoServlet#handleWithHooks} exactly, but takes a
   * {@link NeoCrudHandler} directly instead of an {@code HttpServletRequest}/
   * {@code HttpServletResponse} pair — neither of which the original method actually reads
   * in its body. This lets non-HTTP callers (namely {@link BatchService}, which documents
   * itself as running "without going through HTTP") honor a custom handler too: before this,
   * {@code BatchService#createRecord} always called {@link NeoCrudHandler#handleDefault}
   * directly, silently skipping any entity's configured Java_Qualifier — for an entity whose
   * handler owns nested-record creation logic the generic CRUD path knows nothing about
   * (e.g. Contacts' {@code locationAddress}, which creates a C_Location the join entity's
   * own fields never expose), the record was created with none of the handler's real logic
   * applied, silently violating downstream NOT NULL constraints.
   */
  static NeoResponse handleWithHooks(String javaQualifier, NeoContext context, NeoCrudHandler crudHandler) {
    return handleWithHooks(javaQualifier, context, crudHandler,
        NeoExtensionChannel.REST_SINGLE);
  }

  /**
   * Same dispatch, with the {@link NeoExtensionChannel} stated by the caller.
   *
   * <p>Only the trace differs: the channel names which caller family this dispatch came from, and
   * for the REST channels both values select the very same resolver, so {@code REST_SINGLE} and
   * {@code REST_BATCH} resolve identically. {@link BatchService} passes {@code REST_BATCH} so a
   * dispatch made for one operation of a {@code /batch} request is distinguishable in the trace
   * from the same entity's direct HTTP write — the two reach this method through different code
   * and have already diverged once (see this method's javadoc).</p>
   *
   * @param javaQualifier the entity's {@code Java_Qualifier}
   * @param context       the request context
   * @param crudHandler   the generic CRUD service, run as the wrapped default step
   * @param channel       the caller family, recorded in the trace
   * @return the response the caller will send
   */
  static NeoResponse handleWithHooks(String javaQualifier, NeoContext context,
      NeoCrudHandler crudHandler, NeoExtensionChannel channel) {
    // A lambda, not crudHandler::handleDefault: the reference is only dereferenced when the
    // default step actually runs, as before.
    return handleWithDefaultStep(javaQualifier, context, ctx -> crudHandler.handleDefault(ctx),
        channel);
  }

  /**
   * The same pipeline with the default step supplied by the caller (ETP-5558): for a caller that
   * has no {@link NeoCrudHandler} — it needs the servlet — but must still run a customization the
   * way REST does (resolution through {@link NeoExtensionDispatcher}, error short-circuit,
   * {@code afterHandle}, audit-token refresh, an exception answered as 500). The account's movement
   * and transfer actions call the {@code financial-account-transactions} endpoint through here.
   *
   * @param defaultStep what runs when there is no customization, or when it declines
   */
  static NeoResponse handleWithDefaultStep(String javaQualifier, NeoContext context,
      Function<NeoContext, NeoResponse> defaultStep, NeoExtensionChannel channel) {
    try {
      NeoExtensionRequest request = NeoExtensionRequest.builder()
          .qualifier(javaQualifier)
          .specName(context.getSpecName())
          .entityName(context.getEntityName())
          .surface(NeoExtensionSurface.of(context))
          .channel(channel)
          .context(context)
          .build();

      NeoExtensionResult preDispatch = NeoExtensionDispatcher.dispatch(request);
      NeoHandler handler = preDispatch.customization();
      if (handler == null) {
        // ETP-5415: a blank qualifier is not an anomaly — it is the normal state of an entity that
        // has no customization, and callers no longer filter those out before dispatching (the
        // blank-qualifier early returns were removed so @NeoExtension is reachable everywhere).
        // Warning on it would fire once per batch operation for ordinary entities and drown the
        // case the warning exists for: a qualifier that IS configured and resolves to nothing.
        if (StringUtils.isNotBlank(javaQualifier)) {
          log.warn("No handler found for qualifier '{}', falling back to default", javaQualifier);
        }
        return defaultStep.apply(context);
      }

      NeoResponse preResult = preDispatch.response();
      if (preResult != null) {
        // Error responses from the pre-hook short-circuit the whole pipeline.
        // afterHandle is a post-CRUD side effect (e.g. auto-filling adoption dates) and must
        // NOT run when the pre-hook rejected the request — doing so could commit unintended
        // writes (via OBDal.flush) into a transaction that is about to be rolled back or
        // that was already partially modified, leading to corrupt state.
        if (preResult.getHttpStatus() >= 400) {
          return preResult;
        }
        return runPostHook(request, handler, preResult);
      }

      NeoResponse defaultResult = defaultStep.apply(context);

      // Mirrors the pre-hook branch above: afterHandle is a post-CRUD side effect and must
      // NOT run when the default CRUD write itself failed (e.g. invalid "updated" concurrency
      // token, validation error) — otherwise a rejected save could still trigger writes
      // (via OBDal.flush) meant to follow a successful one.
      if (defaultResult != null && defaultResult.getHttpStatus() >= 400) {
        return defaultResult;
      }

      return runPostHook(request, handler, defaultResult);
    } catch (Exception e) {
      log.error("Error executing hook handler: {}", javaQualifier, e);
      return NeoResponse.error(500, "Hook handler error: " + e.getMessage());
    }
  }

  /**
   * Runs {@code afterHandle} over an already-produced write/read result and returns the response
   * the client will actually receive.
   *
   * <p>Both branches of {@link #handleWithHooks} end this way — the one whose result came from the
   * handler's own pre-hook, and the one whose result came from the default CRUD service — and they
   * used to spell it out separately. Extracting it is what makes the ETP-5262 refresh cover both
   * branches by construction instead of by remembering to add it twice; the post-hook contract
   * ("{@code null} keeps the previous result") is now stated exactly once, next to the correction
   * that contract makes necessary.
   *
   * <p>The refresh runs on the EFFECTIVE response, whichever of the two it is: a post-hook that
   * writes to its own record invalidates the {@code updated} token in a body that was serialised
   * before it ran, and a handler that builds a replacement response from
   * {@code context.getPreviousResult()} — which is what almost all of them do — carries that stale
   * token straight into it. See {@link NeoAuditTokenRefresh} for why this belongs here rather than
   * in each handler.
   *
   * @param request       the dispatch request built for this operation, whose context's
   *                      {@code previousResult} the dispatcher sets
   * @param handler       the entity's handler; never {@code null} at this point
   * @param previousResult the result {@code afterHandle} is being given the chance to replace
   * @return the post-hook's response when it returned one, otherwise {@code previousResult}
   */
  private static NeoResponse runPostHook(NeoExtensionRequest request, NeoHandler handler,
      NeoResponse previousResult) {
    NeoContext context = request.context();
    // The post phase runs on the instance the pre phase resolved, never on a freshly resolved one:
    // handlers carry per-request state from handle() into afterHandle(), and both resolvers hand
    // out a new @Dependent reference per call. The dispatcher sets previousResult on the context
    // before invoking, exactly as this method used to do inline.
    NeoResponse afterResult = NeoExtensionDispatcher
        .dispatch(request.post(handler).withPreviousResult(previousResult))
        .response();
    NeoResponse effective = afterResult != null ? afterResult : previousResult;
    NeoAuditTokenRefresh.refreshInResponse(context, effective);
    return effective;
  }

  /**
   * Resolve the {@link NeoHandler} whose class carries {@code @Named(qualifier)}.
   *
   * <p><b>This is not the same resolver as {@code NeoHandlerLookup.byQualifier}</b>, and the
   * difference is load-bearing. Matching is done by reading {@code @Named} off the resolved
   * instance's class, so a normal-scoped bean — whose reference is a Weld client proxy, a subclass
   * that does not carry the non-{@code @Inherited} annotation — is silently skipped. That is the
   * behaviour the REST and batch paths have always had, and the {@code @Named}-only rule for
   * handlers exists because of it. Keeping it is deliberate: switching to the CDI-name match would
   * make handlers that today do not resolve here start resolving.</p>
   *
   * <p>The scan is memoised by {@link NeoHandlerResolutionCache}, which without it ran on every
   * single request and instantiated every deployed handler to inspect one. Only the handler's
   * <b>class</b> is cached; the instance is obtained per call, so nothing about a handler's
   * lifecycle changes. On the warm path {@code getInstanceFromStaticBeanManager} applies the very
   * same {@code getReference} that {@code getInstances} applied, to the one bean that matched.</p>
   *
   * @param qualifier the {@code Java_Qualifier} to match
   * @return the matching handler, or {@code null} when none is deployed or the lookup failed
   */
  static NeoHandler lookupHandler(String qualifier) {
    try {
      // The scan already builds the instance it matched; on a cold call that instance IS the
      // answer, so the cold path stays exactly what it was before the cache existed.
      AtomicReference<NeoHandler> scanned = new AtomicReference<>();
      Optional<Class<? extends NeoHandler>> cached =
          NeoHandlerResolutionCache.handlerClass(qualifier, q -> {
            NeoHandler match = scanForHandler(q);
            scanned.set(match);
            return match == null ? Optional.empty() : Optional.of(match.getClass());
          });
      if (!cached.isPresent()) {
        log.warn("No NeoHandler found with @Named(\"{}\")", qualifier);
        return null;
      }
      if (scanned.get() != null) {
        return scanned.get();
      }
      NeoHandler instance = instantiate(cached.get());
      // Fall back to the full scan rather than degrade to "no handler": the cached class is known
      // to have matched once, so failing to instantiate it directly is a resolution problem
      // (a producer-declared bean, a redeployed container), never an answer.
      return instance != null ? instance : scanForHandler(qualifier);
    } catch (Exception e) {
      log.error("Failed to lookup handler with qualifier: {}", qualifier, e);
      return null;
    }
  }

  /** A fresh CDI reference for the cached handler class, or {@code null} if CDI cannot give one. */
  private static NeoHandler instantiate(Class<? extends NeoHandler> handlerClass) {
    try {
      return WeldUtils.getInstanceFromStaticBeanManager(handlerClass);
    } catch (RuntimeException e) {
      log.debug("Cached handler class {} is not directly resolvable, rescanning: {}",
          handlerClass.getName(), e.getMessage());
      return null;
    }
  }

  /** The cold path: instantiate the deployed handlers and take the first matching {@code @Named}. */
  private static NeoHandler scanForHandler(String qualifier) {
    for (NeoHandler handler : WeldUtils.getInstances(NeoHandler.class)) {
      javax.inject.Named named = handler.getClass().getAnnotation(javax.inject.Named.class);
      if (named != null && qualifier.equals(named.value())) {
        return handler;
      }
    }
    return null;
  }

  static SFSpec findSpec(String specName) {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.ilike(SFSpec.PROPERTY_NAME, specName, MatchMode.EXACT));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.setMaxResults(1);
    List<SFSpec> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  static boolean hasWindowAccess(String windowId) {
    return com.etendoerp.go.schemaforge.util.NeoAccessHelper.hasWindowAccess(windowId);
  }

  static boolean hasWindowAccess(String windowId, String httpMethod) {
    return com.etendoerp.go.schemaforge.util.NeoAccessHelper.hasWindowAccess(windowId, httpMethod);
  }

  static boolean hasWindowAccessForSpec(SFSpec spec, String httpMethod) {
    return com.etendoerp.go.schemaforge.util.NeoAccessHelper.hasWindowAccessForSpec(spec, httpMethod);
  }

  static boolean hasReportSpecAccess(SFSpec spec, String httpMethod) {
    return com.etendoerp.go.schemaforge.util.NeoAccessHelper.hasReportSpecAccess(spec, httpMethod);
  }

  static boolean hasProcessAccess(String processId) {
    return com.etendoerp.go.schemaforge.util.NeoAccessHelper.hasProcessAccess(processId);
  }

  static NeoServlet.NeoPathInfo parsePath(String pathInfo) {
    if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
      return new NeoServlet.NeoPathInfo(null, null, null);
    }

    String[] parts = normalizePathParts(pathInfo);
    if (parts.length < 1 || parts[0].isEmpty()) {
      return new NeoServlet.NeoPathInfo(null, null, null);
    }

    String specName = parts[0];
    if (parts.length == 1) {
      return new NeoServlet.NeoPathInfo(specName, null, null);
    }

    return parseEntityPath(specName, parts);
  }

  private static String[] normalizePathParts(String pathInfo) {
    String normalizedPath = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    return normalizedPath.split("/");
  }

  private static NeoServlet.NeoPathInfo parseEntityPath(String specName, String[] parts) {
    String entityName = parts[1];
    if (parts.length < 3) {
      return new NeoServlet.NeoPathInfo(specName, entityName, null);
    }
    NeoServlet.NeoPathInfo subEndpointPath = parseSubEndpointPath(specName, entityName, parts);
    if (subEndpointPath != null) {
      return subEndpointPath;
    }
    String recordId = parts[2];
    return parseActionOrRecordPath(specName, entityName, recordId, parts);
  }

  private static NeoServlet.NeoPathInfo parseSubEndpointPath(String specName, String entityName,
      String[] parts) {
    String thirdSegment = parts[2];
    if ("selectors".equals(thirdSegment)) {
      String selectorField = parts.length >= 4 ? parts[3] : null;
      return new NeoServlet.NeoPathInfo(specName, entityName, null, true, selectorField);
    }
    if ("callout".equals(thirdSegment)) {
      return new NeoServlet.NeoPathInfo(
          specName, entityName, null, false, null, false, null, false, true, false);
    }
    if ("defaults".equals(thirdSegment)) {
      return new NeoServlet.NeoPathInfo(
          specName, entityName, null, false, null, false, null, false, false, true);
    }
    if ("evaluate-display".equals(thirdSegment)) {
      return new NeoServlet.NeoPathInfo(specName, entityName, null, false, null, false, null, true);
    }
    return null;
  }

  private static NeoServlet.NeoPathInfo parseActionOrRecordPath(String specName, String entityName,
      String recordId, String[] parts) {
    if (parts.length >= 4 && "action".equals(parts[3])) {
      String actionName = parts.length >= 5 ? parts[4] : null;
      return new NeoServlet.NeoPathInfo(specName, entityName, recordId, false, null, true,
          actionName);
    }
    return new NeoServlet.NeoPathInfo(specName, entityName, recordId);
  }
}
