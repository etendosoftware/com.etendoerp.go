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

package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link NeoAuditTokenRefresh} (ETP-5262, hardened in ETP-5255).
 *
 * <h2>What is being pinned</h2>
 *
 * A CRUD write serialises its response body <b>before</b> {@code afterHandle} runs. A post-hook
 * that saves the record it just wrote therefore bumps {@code updated} on the row while the body
 * already in flight still carries the pre-write value — and {@code afterHandle} returning
 * {@code null} means "keep that body", so the client caches a token the row has already moved
 * past. Since ETP-5122 the React client harvests exactly that value, so the user's very first
 * edit of the record they just created was refused with a {@code stale_record} 409: a conflict
 * with nobody. Reported on the Users window; {@code AbstractInvoiceHeaderHandler} does the same
 * thing to {@code C_Invoice} on every invoice write.
 *
 * <p>The tests are grouped by the two halves of the fix that can fail independently:
 * {@link Refreshing} — the correction itself, and the guards that keep it from firing on
 * responses that have no record in them; and {@link DispatchSiteInventory} — the invariant that
 * every place {@code afterHandle} is invoked from actually routes through this class, which is
 * what stops the defect being reintroduced by a new dispatch path rather than by a change to
 * this file.
 *
 * <p>No test asserts a literal token. The emitted offset is asserted structurally, against the
 * JVM's own rules for the instant in question, so the suite passes in any timezone — an
 * expectation spelling out {@code +0200} would pass only in one, which is precisely the class of
 * mistake ETP-5255 was fixing on the production side.
 */
class NeoAuditTokenRefreshTest {

  /** The DAL entity name the mocked tab resolves to. */
  private static final String ENTITY = "ADUser";
  private static final String RECORD_ID = "100";

  /**
   * The row's real {@code updated}, as a civil literal: {@link Timestamp#valueOf} reads it in the
   * default zone, so every expectation derived from it moves with the runner's timezone.
   */
  private static final String STORED_LITERAL = "2026-09-01 21:43:02";

  /**
   * The value the pre-hook response carried — one second older than the row, which is exactly the
   * window in which the original defect became visible ({@code NeoRecordVersion} zeroes
   * milliseconds, so a stale token only differs once the post-hook's flush crosses a wall-clock
   * second).
   */
  private static final String STALE_TOKEN = "2026-09-01T21:43:01-03:00";

  private MockedStatic<OBDal> obDalStatic;
  private OBDal obDal;

  @BeforeEach
  void setUp() {
    obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    if (obDalStatic != null) {
      obDalStatic.close();
    }
  }

  /** A context that satisfies every precondition: a CRUD write on a resolved tab. */
  private NeoContext crudWrite(String method) {
    Table table = mock(Table.class);
    when(table.getName()).thenReturn(ENTITY);
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    return NeoContext.builder()
        .specName("users")
        .entityName("header")
        .httpMethod(method)
        .endpointType(NeoEndpointType.CRUD)
        .adTab(tab)
        .recordId(RECORD_ID)
        .build();
  }

  /** Stubs the row read so it reports {@code STORED_LITERAL} as its {@code updated}. */
  private void givenStoredRow() {
    User stored = mock(User.class);
    when(stored.getUpdated()).thenReturn(Timestamp.valueOf(STORED_LITERAL));
    when(obDal.get(eq(ENTITY), any())).thenReturn(stored);
  }

  /** Core's {@code {"response":{"data":[record]}}} write envelope. */
  private static JSONObject envelope(JSONObject written) throws JSONException {
    return new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(written)));
  }

  private static JSONObject recordWithToken(String token) throws JSONException {
    return new JSONObject().put("id", RECORD_ID).put("updated", token);
  }

  private static JSONObject firstRecordOf(JSONObject body) throws JSONException {
    return body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
  }

  @Nested
  @DisplayName("refreshing the token")
  class Refreshing {

    @Test
    @DisplayName("a token the row has moved past is replaced — the ETP-5262 regression")
    void staleTokenIsReplaced() throws JSONException {
      givenStoredRow();
      JSONObject body = envelope(recordWithToken(STALE_TOKEN));

      NeoAuditTokenRefresh.refreshInBody(crudWrite("POST"), body);

      String refreshed = firstRecordOf(body).getString("updated");
      assertEquals(NeoDateFormat.toAuditToken(Timestamp.valueOf(STORED_LITERAL)), refreshed);
    }

    /**
     * The offset is what the whole thing turns on: core's reader
     * ({@code JsonUtils.convertFromXSDToJavaFormat}) appends {@code "+0000"} to a token that
     * carries none rather than refusing it, so an offsetless value is silently re-read as UTC and
     * the comparison fails by exactly the server's offset. Asserted by PARSING the offset out and
     * comparing it to the JVM's own rules, never against a literal — a hardcoded {@code +0200}
     * would pass in one timezone only.
     */
    @Test
    @DisplayName("the emitted token carries the server's zone offset")
    void emittedTokenCarriesAZoneOffset() throws JSONException {
      givenStoredRow();
      JSONObject body = envelope(recordWithToken(STALE_TOKEN));

      NeoAuditTokenRefresh.refreshInBody(crudWrite("PUT"), body);

      String refreshed = firstRecordOf(body).getString("updated");
      // ETP-5283: core's writer is a SimpleDateFormat with `ZZZZZ`, i.e. RFC-822 (`+0200`), but
      // toAuditToken pairs it with convertToCorrectXSDFormat as core documents, so the refreshed
      // token carries the XSD colon offset (`+02:00`). Hence `XXX`, not `Z`.
      OffsetDateTime parsed = OffsetDateTime.parse(
          refreshed, DateTimeFormatter.ofPattern(NeoDateFormat.ISO_DATETIME + "XXX"));
      Timestamp storedTs = Timestamp.valueOf(STORED_LITERAL);
      ZoneOffset expected = ZoneId.systemDefault().getRules().getOffset(storedTs.toInstant());

      assertEquals(expected, parsed.getOffset());
      assertEquals(LocalDateTime.of(2026, 9, 1, 21, 43, 2), parsed.toLocalDateTime());
      assertTrue(refreshed.length() > NeoDateFormat.toWireDateTime(storedTs).length(),
          "the token must be the offsetless rendering PLUS an offset, got: " + refreshed);
    }

    /**
     * The flat shape a handler that fully overrode the write in its pre-hook can return. Only
     * treated as a record when it actually carries a token, so an envelope with an empty
     * {@code data} array is not mistaken for one.
     */
    @Test
    @DisplayName("a flat record body is refreshed too")
    void flatBodyIsRefreshed() throws JSONException {
      givenStoredRow();
      JSONObject body = recordWithToken(STALE_TOKEN);

      NeoAuditTokenRefresh.refreshInBody(crudWrite("POST"), body);

      assertEquals(NeoDateFormat.toAuditToken(Timestamp.valueOf(STORED_LITERAL)),
          body.getString("updated"));
    }

    @Test
    @DisplayName("a token that already matches the row is left byte-identical")
    void unchangedTokenIsLeftAlone() throws JSONException {
      givenStoredRow();
      String current = NeoDateFormat.toAuditToken(Timestamp.valueOf(STORED_LITERAL));
      JSONObject body = envelope(recordWithToken(current));

      NeoAuditTokenRefresh.refreshInBody(crudWrite("PATCH"), body);

      assertEquals(current, firstRecordOf(body).getString("updated"));
    }

    /**
     * A POST has no id in the URL — the created record's id exists only in the response, which is
     * the case this class is entirely about. So the record's own id wins over the context's.
     */
    @Test
    @DisplayName("the id is read from the record, so a POST response is covered")
    void idComesFromTheRecord() throws JSONException {
      givenStoredRow();
      NeoContext context = NeoContext.builder()
          .httpMethod("POST")
          .endpointType(NeoEndpointType.CRUD)
          .adTab(crudWrite("POST").getAdTab())
          .build();               // no recordId: a create never has one
      JSONObject body = envelope(recordWithToken(STALE_TOKEN));

      NeoAuditTokenRefresh.refreshInBody(context, body);

      assertEquals(NeoDateFormat.toAuditToken(Timestamp.valueOf(STORED_LITERAL)),
          firstRecordOf(body).getString("updated"));
    }

    /** And the context's id is the fallback for a PUT whose response shape omits it. */
    @Test
    @DisplayName("the context id is the fallback when the record carries none")
    void contextIdIsTheFallback() throws JSONException {
      givenStoredRow();
      JSONObject body = envelope(new JSONObject().put("updated", STALE_TOKEN));

      NeoAuditTokenRefresh.refreshInBody(crudWrite("PUT"), body);

      assertEquals(NeoDateFormat.toAuditToken(Timestamp.valueOf(STORED_LITERAL)),
          firstRecordOf(body).getString("updated"));
    }
  }

  /**
   * The guards. Each of these is a response that has no record whose token could be corrected;
   * touching it would either add a field {@code NeoFieldFilter} chose to withhold or lose a write
   * that already succeeded.
   */
  @Nested
  @DisplayName("responses that must be left alone")
  class LeftAlone {

    @Test
    @DisplayName("a non-CRUD endpoint type is skipped — an ACTION result is not a record")
    void nonCrudIsSkipped() throws JSONException {
      givenStoredRow();
      NeoContext context = NeoContext.builder()
          .httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION)
          .adTab(crudWrite("POST").getAdTab())
          .recordId(RECORD_ID)
          .build();
      JSONObject body = envelope(recordWithToken(STALE_TOKEN));

      NeoAuditTokenRefresh.refreshInBody(context, body);

      assertEquals(STALE_TOKEN, firstRecordOf(body).getString("updated"));
    }

    @Test
    @DisplayName("a read is skipped, and so is DELETE — no row left to read a token from")
    void nonWriteMethodsAreSkipped() throws JSONException {
      givenStoredRow();
      for (String method : new String[] { "GET", "DELETE" }) {
        JSONObject body = envelope(recordWithToken(STALE_TOKEN));

        NeoAuditTokenRefresh.refreshInBody(crudWrite(method), body);

        assertEquals(STALE_TOKEN, firstRecordOf(body).getString("updated"),
            method + " must not be refreshed");
      }
    }

    @Test
    @DisplayName("an error response is skipped — a refused write did not change the row")
    void errorResponseIsSkipped() throws JSONException {
      givenStoredRow();
      JSONObject body = envelope(recordWithToken(STALE_TOKEN));

      NeoAuditTokenRefresh.refreshInResponse(crudWrite("PUT"), new NeoResponse(409, body));

      assertEquals(STALE_TOKEN, firstRecordOf(body).getString("updated"));
    }

    /**
     * The class never ADDS an {@code updated} key. Its job is to correct a value the response
     * already publishes, not to expose a field the field filter withheld.
     */
    @Test
    @DisplayName("a record with no updated stays without one")
    void recordWithoutTokenGainsNone() throws JSONException {
      givenStoredRow();
      JSONObject body = envelope(new JSONObject().put("id", RECORD_ID));

      NeoAuditTokenRefresh.refreshInBody(crudWrite("PUT"), body);

      assertFalse(firstRecordOf(body).has("updated"));
    }

    /**
     * Best-effort by construction: getting the token wrong costs one spurious 409 the user can
     * retry past, whereas throwing here would lose a write that already succeeded and is about to
     * be committed.
     */
    @Test
    @DisplayName("an unreadable row leaves the body unchanged and does not throw")
    void unreadableRowIsANoOp() throws JSONException {
      when(obDal.get(eq(ENTITY), any())).thenThrow(new IllegalStateException("session closed"));
      JSONObject body = envelope(recordWithToken(STALE_TOKEN));

      assertDoesNotThrow(() -> NeoAuditTokenRefresh.refreshInBody(crudWrite("PUT"), body));

      assertEquals(STALE_TOKEN, firstRecordOf(body).getString("updated"));
    }

    @Test
    @DisplayName("a row that is not Traceable has no audit token to read")
    void nonTraceableRowIsANoOp() throws JSONException {
      when(obDal.get(eq(ENTITY), any())).thenReturn(mock(BaseOBObject.class));
      JSONObject body = envelope(recordWithToken(STALE_TOKEN));

      NeoAuditTokenRefresh.refreshInBody(crudWrite("PUT"), body);

      assertEquals(STALE_TOKEN, firstRecordOf(body).getString("updated"));
    }

    @Test
    @DisplayName("a null body and a null response are no-ops")
    void nullsAreNoOps() {
      assertDoesNotThrow(() -> NeoAuditTokenRefresh.refreshInBody(crudWrite("PUT"), null));
      assertDoesNotThrow(() -> NeoAuditTokenRefresh.refreshInBody(null, new JSONObject()));
      assertDoesNotThrow(() -> NeoAuditTokenRefresh.refreshInResponse(crudWrite("PUT"), null));
    }

    /** A sub-hook context carries no resolved tab, and cannot be a CRUD write anyway. */
    @Test
    @DisplayName("a context with no resolved tab is a no-op")
    void missingTabIsANoOp() throws JSONException {
      JSONObject body = envelope(recordWithToken(STALE_TOKEN));
      NeoContext context = NeoContext.builder()
          .httpMethod("PUT")
          .endpointType(NeoEndpointType.CRUD)
          .build();

      NeoAuditTokenRefresh.refreshInBody(context, body);

      assertEquals(STALE_TOKEN, firstRecordOf(body).getString("updated"));
    }
  }

  /**
   * The invariant that keeps this class of bug closed.
   *
   * <p>The defect ETP-5262 fixed is in a <b>contract</b>, not in one handler: any dispatcher that
   * invokes {@code afterHandle} and then ships the pre-hook body publishes a stale token. Fixing
   * it centrally only works for as long as every dispatch path goes through
   * {@link NeoAuditTokenRefresh} — a new one added without it silently reintroduces a failure that
   * shows up in production as an unreproducible 409 and in the test suite as nothing at all.
   *
   * <p>There is no behavioural way to test a dispatch path that does not exist yet, so this is a
   * deliberate <b>inventory</b> assertion over the module's sources, not a behaviour assertion
   * dressed up as one (the behaviour of each existing site is covered where it lives:
   * {@code NeoServletSupportTest.PostHookAuditToken} and
   * {@code McpHookExecutorAuditTokenTest}). It is allowed to be brittle in one direction only: it
   * fails when a dispatch site is ADDED, which is exactly when a human needs to decide whether
   * that site needs the refresh. If it fails, do not delete the assertion — either route the new
   * site through {@code NeoAuditTokenRefresh}, or add it to {@link #EXEMPT} with the reason it
   * cannot carry a CRUD write.
   *
   * <p>The scan reads each file's code only ({@link NeoAuditTokenRefreshTest#codeOf}): a mention
   * of {@code .afterHandle(} in a comment is prose about another class, not a dispatch site, and
   * counting it produced a false positive on {@code AbstractInvoiceHeaderHandler} (ETP-5255).
   */
  @Nested
  @DisplayName("afterHandle dispatch-site inventory")
  class DispatchSiteInventory {

    /**
     * Dispatch sites that legitimately do not call the refresh, each with the reason it cannot
     * reach a CRUD write — which is the only case {@link NeoAuditTokenRefresh} acts on.
     */
    private static final Set<String> EXEMPT = Set.of(
        // The MCP DEFAULTS endpoint: a GET that resolves field defaults. Not a write, and there is
        // no record whose token could be corrected.
        "com/etendoerp/go/mcp/McpToolRouter.java");

    /** The sites that DO carry a write whose new token must reach the response. */
    private static final Set<String> MUST_REFRESH = Set.of(
        "com/etendoerp/go/schemaforge/NeoServletSupport.java",
        "com/etendoerp/go/mcp/McpHookExecutor.java",
        // ETP-5255: no longer exempt. Sub-endpoint handlers (actions, callouts) DO persist changes
        // in afterHandle(), so NeoHookDispatcher#runPostHook now routes its effective result
        // through NeoAuditTokenRefresh — without it the response shipped the pre-hook token and
        // the caller's next write came back as a false 409.
        "com/etendoerp/go/schemaforge/NeoHookDispatcher.java");

    @Test
    @DisplayName("every afterHandle dispatch site either refreshes the token or is a known exemption")
    void everyDispatchSiteIsAccountedFor() {
      Path sourceRoot = moduleSourceRoot();
      assertNotNull(sourceRoot,
          "could not locate the module's src/ directory from " + Paths.get("").toAbsolutePath()
              + " — fix the locator in this test rather than deleting the invariant");

      List<String> dispatchSites = new ArrayList<>();
      List<String> withoutRefresh = new ArrayList<>();
      for (Path file : javaFilesUnder(sourceRoot)) {
        // Comments stripped first: the scan below is a text match, and a file that merely MENTIONS
        // another class's hook in prose is not a dispatch site. AbstractInvoiceHeaderHandler was
        // counted as one on the strength of the sentence "is also invoked from
        // InvoiceLineHandler.afterHandle() (line save)". Exempting that file would have been the
        // wrong repair twice over — it would file a non-site as an exempt site, and it would then
        // mask a REAL dispatch site later added to the same file.
        String source = codeOf(file);
        // The INVOCATION, not the `@Override public NeoResponse afterHandle(...)` declarations the
        // handlers carry: a handler implements the hook, a dispatcher calls it.
        if (!source.contains(".afterHandle(")) {
          continue;
        }
        String relative = relativeUnixPath(sourceRoot, file);
        dispatchSites.add(relative);
        if (!source.contains("NeoAuditTokenRefresh")) {
          withoutRefresh.add(relative);
        }
      }

      // A handler calling `super.afterHandle(context)` up its own inheritance chain is not a
      // dispatch site; it is one implementation delegating to another, and the dispatcher that
      // invoked it has already been counted.
      dispatchSites.removeIf(NeoAuditTokenRefreshTest.DispatchSiteInventory::isSuperDelegationOnly);
      withoutRefresh.removeIf(NeoAuditTokenRefreshTest.DispatchSiteInventory::isSuperDelegationOnly);

      assertTrue(dispatchSites.containsAll(MUST_REFRESH),
          "a known dispatch site disappeared — expected " + MUST_REFRESH + ", found "
              + dispatchSites);
      assertEquals(EXEMPT, Set.copyOf(withoutRefresh),
          "a dispatch site invokes afterHandle without routing through NeoAuditTokenRefresh."
              + " Either call the refresh there, or add the file to EXEMPT with the reason it"
              + " cannot carry a CRUD write (ETP-5262). Sites found: " + dispatchSites);
    }

    private static boolean isSuperDelegationOnly(String relativePath) {
      Path sourceRoot = moduleSourceRoot();
      String source = codeOf(sourceRoot.resolve(relativePath));
      return !source.replace("super.afterHandle(", "").contains(".afterHandle(");
    }
  }

  // ── source-tree helpers, shared by the inventory test ──────────────────────────────────────

  /**
   * The module's {@code src} directory, located by walking up from the working directory — which
   * is the Etendo root under {@code gradle test} but the module itself under some IDE runners.
   *
   * @return the directory, or {@code null} when neither layout matches
   */
  private static Path moduleSourceRoot() {
    Path here = Paths.get("").toAbsolutePath();
    for (Path dir = here; dir != null; dir = dir.getParent()) {
      Path fromRoot = dir.resolve("modules/com.etendoerp.go/src");
      if (Files.isDirectory(fromRoot)) {
        return fromRoot;
      }
      Path fromModule = dir.resolve("src/com/etendoerp/go");
      if (Files.isDirectory(fromModule)) {
        return dir.resolve("src");
      }
    }
    return null;
  }

  private static List<Path> javaFilesUnder(Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      return paths.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path file) {
    try {
      return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The file's CODE, with every comment removed — the only text the inventory scan may look at.
   *
   * <p>A single state machine rather than a regex because the two constructs interleave: a
   * {@code "//"} inside a string literal opens no comment, and a {@code '"'} inside a comment
   * opens no literal. Comment bodies are replaced by a space so no two identifiers are welded
   * together, and line breaks inside a block comment are preserved so nothing else that reads this
   * text can be thrown off by them.
   *
   * <p>Character literals are tracked alongside strings for the sole reason that {@code '"'} is a
   * legal one, and mistaking it for the start of a string would swallow the rest of the file.
   */
  private static String codeOf(Path file) {
    String source = read(file);
    StringBuilder out = new StringBuilder(source.length());
    boolean inLineComment = false;
    boolean inBlockComment = false;
    boolean inString = false;
    boolean inChar = false;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
      if (inLineComment) {
        if (c == '\n') {
          inLineComment = false;
          out.append(c);
        }
        continue;
      }
      if (inBlockComment) {
        if (c == '*' && next == '/') {
          inBlockComment = false;
          i++;
          out.append(' ');
        } else if (c == '\n') {
          out.append(c);
        }
        continue;
      }
      if (inString || inChar) {
        out.append(c);
        if (c == '\\') {
          // An escape consumes the next character, so a `\"` never closes the literal.
          if (i + 1 < source.length()) {
            out.append(next);
            i++;
          }
        } else if (inString && c == '"') {
          inString = false;
        } else if (inChar && c == '\'') {
          inChar = false;
        }
        continue;
      }
      if (c == '/' && next == '/') {
        inLineComment = true;
        out.append(' ');
        i++;
        continue;
      }
      if (c == '/' && next == '*') {
        inBlockComment = true;
        out.append(' ');
        i++;
        continue;
      }
      if (c == '"') {
        inString = true;
      } else if (c == '\'') {
        inChar = true;
      }
      out.append(c);
    }
    return out.toString();
  }

  private static String relativeUnixPath(Path root, Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }
}
