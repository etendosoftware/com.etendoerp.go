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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * The two write gates ETP-5335 put in front of {@code neo_create} and {@code neo_update} —
 * {@link McpQuerySupport#writeGate} as consumed by
 * {@link McpWriteRequestSupport#mapFieldsToDalProperties}.
 *
 * <h2>Why two gates and not one</h2>
 * <p>They answer different questions and they refuse differently on purpose.</p>
 * <ul>
 *   <li><b>Excluded</b> ({@code ETGO_SF_FIELD.ISINCLUDED = 'N'}) — the field is not part of the
 *       agent surface at all. The refusal is worded to be <b>indistinguishable from the answer for
 *       a name that is not a column of the table</b>: two telling-apart answers would let any
 *       caller enumerate the underlying AD table by probing keys and reading which refusal came
 *       back. That indistinguishability is a security property, so it is asserted here rather than
 *       left to the wording of a javadoc.</li>
 *   <li><b>Read-only</b> — the field IS on the surface and {@code neo_schema} already publishes it
 *       carrying {@code readOnly: true}, so naming the reason repeats what the caller was told
 *       before it wrote and reveals nothing.</li>
 * </ul>
 *
 * <h2>The exemptions, and the one that was dropped</h2>
 * <p>The read-only predicate is {@code NeoFieldFilter}'s, minus the entity-wide
 * {@code Java_Qualifier} exemption: on the REST path that exemption exists because the handler
 * pre-hook has already run and injected values by the time the body is inspected, while on the MCP
 * path the mapping runs on the caller's own {@code fields} argument. Keeping it cost most of the
 * gate — 79 of the 128 writable entities declare a qualifier — and a live probe caught it
 * accepting {@code documentNo} on {@code sales-order/header}. Its absence is asserted, because
 * nothing about a missing exemption is visible in a signature.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ETP-5335 — the MCP write gates (IMP-39 excluded, IMP-48/IMP-30 read-only)")
class McpWriteGateTest {

  private static final String ENTITY_NAME = "Order";
  private static final String TABLE_ID = "tbl-order";
  private static final String TABLE_NAME = "C_Order";

  @Mock
  private OBDal mockOBDal;
  @Mock
  private ModelProvider mockModelProvider;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<ModelProvider> modelProviderMock;

  private Entity dalEntity;
  private Tab adTab;
  private final List<SFField> curatedRows = new ArrayList<>();
  private int fixtureSeq;

  @BeforeEach
  void setUp() {
    // The other classes in this package clear the section registry without resetting the
    // bootstrap flag; resolving MCP_CONFIG needs the real `fields` section registered.
    McpConfigSections.resetForTests();
    McpConfigCache.invalidateAll();

    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(mockOBDal);
    modelProviderMock = mockStatic(ModelProvider.class);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(mockModelProvider);

    dalEntity = mock(Entity.class);
    when(dalEntity.getName()).thenReturn(ENTITY_NAME);
    when(mockModelProvider.getEntityByTableId(TABLE_ID)).thenReturn(dalEntity);
    when(mockModelProvider.getEntityByTableName(TABLE_NAME)).thenReturn(dalEntity);

    // The real Entity throws when asked for a property it does not have; a bare mock would
    // return null and send resolveFilterProperty into an NPE instead of its refusal.
    when(dalEntity.getProperty(anyString()))
        .thenThrow(new IllegalArgumentException("no such property"));

    Table table = mock(Table.class);
    when(table.getId()).thenReturn(TABLE_ID);
    when(table.getDBTableName()).thenReturn(TABLE_NAME);
    adTab = mock(Tab.class);
    when(adTab.getTable()).thenReturn(table);

    @SuppressWarnings("unchecked")
    OBCriteria<SFField> criteria = mock(OBCriteria.class);
    when(mockOBDal.createCriteria(SFField.class)).thenReturn(criteria);
    when(criteria.list()).thenReturn(curatedRows);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    modelProviderMock.close();
    curatedRows.clear();
    McpConfigSections.resetForTests();
    McpConfigCache.invalidateAll();
  }

  // ── fixture helpers ───────────────────────────────────────────────────

  /**
   * Declare a DAL property reachable both by its own name and by its DB column name — the two
   * lookups {@code mapFieldsToDalProperties} tries, in that order.
   *
   * @param propertyName the DAL property name (what the gate's sets are keyed by)
   * @param columnName   the DB column name
   * @return the property mock
   */
  private Property declareProperty(String propertyName, String columnName) {
    Property prop = mock(Property.class);
    when(prop.getName()).thenReturn(propertyName);
    when(dalEntity.getProperty(propertyName, false)).thenReturn(prop);
    when(dalEntity.getPropertyByColumnName(columnName, false)).thenReturn(prop);
    when(dalEntity.getPropertyByColumnName(propertyName, false)).thenReturn(prop);
    return prop;
  }

  /**
   * Add one curated {@code ETGO_SF_FIELD} row for a declared property.
   *
   * @param columnName   the DB column name, which must match a {@link #declareProperty} call
   * @param included     the row's {@code ISINCLUDED}
   * @param readOnly     the row's {@code ISREADONLY}
   * @param defaultValue the AD column's default, or {@code null} for none
   * @param mcpConfig    the entity's {@code MCP_CONFIG} body, or {@code null} for none
   */
  private void curate(String columnName, Boolean included, Boolean readOnly, String defaultValue,
      String mcpConfig) {
    String id = "fx-" + (fixtureSeq++);
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn(columnName);
    when(column.getDefaultValue()).thenReturn(defaultValue);

    // Built before the stubbing below: nesting a when(...) inside another when(...)'s argument
    // is what UnfinishedStubbingException is for.
    SFEntity owningEntity = sfEntityFor(id, mcpConfig);

    SFField field = mock(SFField.class);
    when(field.getId()).thenReturn("field-" + id);
    when(field.getADColumn()).thenReturn(column);
    when(field.getVisibility()).thenReturn(null);
    when(field.isIncluded()).thenReturn(included);
    when(field.isReadOnly()).thenReturn(readOnly);
    when(field.isBusinessCritical()).thenReturn(Boolean.FALSE);
    when(field.getETGOSFEntity()).thenReturn(owningEntity);
    curatedRows.add(field);
  }

  private SFEntity sfEntityFor(String id, String mcpConfig) {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("sf-entity-" + id);
    when(entity.getName()).thenReturn(ENTITY_NAME);
    when(entity.get(SFEntity.PROPERTY_MCPCONFIG)).thenReturn(mcpConfig);
    return entity;
  }

  /** The SchemaForge entity handed to the gate — its own identity never matters to it. */
  private SFEntity specEntity() {
    return sfEntityFor("spec", null);
  }

  private static JSONObject fields(String key, Object value) throws Exception {
    JSONObject body = new JSONObject();
    body.put(key, value);
    return body;
  }

  private JSONObject write(JSONObject body, SFEntity sfEntity) throws Exception {
    return McpWriteRequestSupport.mapFieldsToDalProperties(body, adTab, sfEntity, new TreeSet<>());
  }

  /** The same write, keeping the server-owned report the five-argument overload builds. */
  private JSONObject write(JSONObject body, SFEntity sfEntity, JSONObject serverOwned)
      throws Exception {
    return McpWriteRequestSupport.mapFieldsToDalProperties(body, adTab, sfEntity, new TreeSet<>(),
        serverOwned);
  }

  // ── excluded fields (IMP-39) ──────────────────────────────────────────

  @Nested
  @DisplayName("an excluded field is refused, and the refusal admits nothing")
  class ExcludedFields {

    @Test
    @DisplayName("neo_create: a field the spec excludes answers 422 field_not_allowed")
    void createRefusesAnExcludedField() throws Exception {
      declareProperty("orderReference", "POReference");
      curate("POReference", Boolean.FALSE, Boolean.FALSE, null, null);

      McpRoutingException thrown = assertThrows(McpRoutingException.class,
          () -> write(fields("orderReference", "PO-1"), specEntity()));

      JSONObject envelope = thrown.toEnvelope();
      assertEquals(422, envelope.getInt(McpConstants.KEY_STATUS));
      assertEquals(McpConstants.ERROR_FIELD_NOT_ALLOWED,
          envelope.getString(McpConstants.KEY_ERROR));
      assertEquals("orderReference", envelope.getString(McpConstants.PARAM_FIELD));
    }

    /**
     * The same key spelled as its DB column. A caller that reaches the field by either name must
     * get the same answer — otherwise the column spelling is a way around the gate.
     */
    @Test
    @DisplayName("the DB column spelling of the same field is refused identically")
    void theColumnSpellingIsRefusedToo() throws Exception {
      declareProperty("orderReference", "POReference");
      curate("POReference", Boolean.FALSE, Boolean.FALSE, null, null);

      McpRoutingException thrown = assertThrows(McpRoutingException.class,
          () -> write(fields("POReference", "PO-1"), specEntity()));
      assertEquals(McpConstants.ERROR_FIELD_NOT_ALLOWED,
          thrown.toEnvelope().getString(McpConstants.KEY_ERROR));
    }

    /**
     * <b>The security property, asserted rather than asserted-in-prose.</b> A caller must not be
     * able to tell "this field exists and was curated out" from "no such field", because the
     * difference between the two answers is a column-enumeration oracle over the AD table.
     *
     * <p>The write path cannot show it — an unmapped key is reported, not refused — so it is
     * pinned on the filter path, where both cases genuinely reach a refusal. The two envelopes
     * must be byte-identical once the key name is substituted out.</p>
     */
    @Test
    @DisplayName("filtering: an excluded field and a field that does not exist give the same "
        + "answer")
    void theRefusalsAreIndistinguishable() throws Exception {
      declareProperty("orderReference", "POReference");
      declareProperty("documentNo", "DocumentNo");
      curate("POReference", Boolean.FALSE, Boolean.FALSE, null, null);
      curate("DocumentNo", Boolean.TRUE, Boolean.FALSE, null, null);
      SFEntity sfEntity = specEntity();

      String excluded = filterRefusal("orderReference", sfEntity)
          .replace("orderReference", "KEY");
      String absent = filterRefusal("noSuchColumn", sfEntity).replace("noSuchColumn", "KEY");

      assertEquals(excluded, absent,
          "the two refusals differ, which turns the response into a probe for which columns the"
              + " AD table really has — the wording must neither assert nor deny that the field"
              + " exists");
    }

    private String filterRefusal(String key, SFEntity sfEntity) throws Exception {
      McpRoutingException thrown = assertThrows(McpRoutingException.class,
          () -> McpQuerySupport.buildWhereFromFilters(fields(key, "x"), adTab, sfEntity, null));
      return thrown.toEnvelope().toString();
    }

    /**
     * The refusal must not leak the reason in its own wording either — an envelope that says
     * "excluded"/"curated out"/"hidden" is the same oracle spelled differently.
     */
    @Test
    @DisplayName("the wording names no reason a caller could read as confirmation")
    void theWordingConfirmsNothing() throws Exception {
      declareProperty("orderReference", "POReference");
      curate("POReference", Boolean.FALSE, Boolean.FALSE, null, null);

      McpRoutingException thrown = assertThrows(McpRoutingException.class,
          () -> write(fields("orderReference", "PO-1"), specEntity()));
      String envelope = thrown.toEnvelope().toString().toLowerCase(Locale.ROOT);

      for (String leak : List.of("exclud", "curat", "hidden", "discarded", "read-only",
          "readonly", "isincluded")) {
        assertFalse(envelope.contains(leak),
            "the refusal says '" + leak + "', which confirms the field exists and why it is "
                + "unreachable");
      }
    }

    /**
     * <b>Absence of curation is not a decision.</b> Over a thousand columns across the curated
     * entities of a typical instance have no {@code ETGO_SF_FIELD} row at all, and a
     * handler-backed entity has none whatsoever — an "included-only" allowlist would have refused
     * every write those entities were ever sent.
     */
    @Test
    @DisplayName("a column with no ETGO_SF_FIELD row at all is not excluded")
    void anUncuratedColumnPassesThrough() throws Exception {
      declareProperty("orderReference", "POReference");
      // deliberately no curate(...) call: the column exists on the table and nowhere in the spec

      JSONObject mapped = write(fields("orderReference", "PO-1"), specEntity());
      assertEquals("PO-1", mapped.getString("orderReference"));
    }

    /**
     * The override the criteria could never have seen. A {@code Restrictions.eq(ISINCLUDED, 'Y')}
     * resolves in the database; {@code MCP_CONFIG} lives in a JSON column nobody joins.
     */
    @Test
    @DisplayName("an MCP_CONFIG fields.included:false excludes a row the spec exposes")
    void theOverrideCanExclude() throws Exception {
      declareProperty("orderReference", "POReference");
      curate("POReference", Boolean.TRUE, Boolean.FALSE, null,
          "{\"fields\":{\"included\":false,\"reason\":\"write-gate fixture\"}}");

      McpRoutingException thrown = assertThrows(McpRoutingException.class,
          () -> write(fields("orderReference", "PO-1"), specEntity()));
      assertEquals(McpConstants.ERROR_FIELD_NOT_ALLOWED,
          thrown.toEnvelope().getString(McpConstants.KEY_ERROR));
    }

    @Test
    @DisplayName("an MCP_CONFIG fields.included:true reclaims a row the spec excluded")
    void theOverrideCanReclaim() throws Exception {
      declareProperty("orderReference", "POReference");
      curate("POReference", Boolean.FALSE, Boolean.FALSE, null,
          "{\"fields\":{\"included\":true,\"reason\":\"write-gate fixture\"}}");

      assertEquals("PO-1",
          write(fields("orderReference", "PO-1"), specEntity()).getString("orderReference"));
    }

    /**
     * The two sets are disjoint by construction, and the excluded one wins: a field that is both
     * excluded and read-only must answer with the code that says less.
     */
    @Test
    @DisplayName("an excluded read-only field answers field_not_allowed, not read_only_field")
    void exclusionOutranksReadOnly() throws Exception {
      declareProperty("orderReference", "POReference");
      curate("POReference", Boolean.FALSE, Boolean.TRUE, null, null);

      McpRoutingException thrown = assertThrows(McpRoutingException.class,
          () -> write(fields("orderReference", "PO-1"), specEntity()));
      assertEquals(McpConstants.ERROR_FIELD_NOT_ALLOWED,
          thrown.toEnvelope().getString(McpConstants.KEY_ERROR));
    }

    /** No spec in hand means no curation to enforce — the two-argument overload's contract. */
    @Test
    @DisplayName("a null SchemaForge entity disables both gates rather than refusing everything")
    void nullSpecEntitySkipsTheGate() throws Exception {
      declareProperty("orderReference", "POReference");
      curate("POReference", Boolean.FALSE, Boolean.TRUE, null, null);

      assertEquals("PO-1", write(fields("orderReference", "PO-1"), null)
          .getString("orderReference"));
    }
  }

  // ── read-only fields (IMP-48 / IMP-30) ────────────────────────────────

  @Nested
  @DisplayName("a read-only field is refused, and the refusal says why")
  class ReadOnlyFields {

    @Test
    @DisplayName("422 read_only_field, distinct from field_not_allowed")
    void readOnlyIsRefusedWithItsOwnCode() throws Exception {
      declareProperty("documentNo", "DocumentNo");
      curate("DocumentNo", Boolean.TRUE, Boolean.TRUE, null, null);

      McpRoutingException thrown = assertThrows(McpRoutingException.class,
          () -> write(fields("documentNo", "SO-9999"), specEntity()));

      JSONObject envelope = thrown.toEnvelope();
      assertEquals(422, envelope.getInt(McpConstants.KEY_STATUS));
      assertEquals(McpConstants.ERROR_READ_ONLY_FIELD,
          envelope.getString(McpConstants.KEY_ERROR));
      assertEquals("documentNo", envelope.getString(McpConstants.PARAM_FIELD));
    }

    /**
     * Naming the reason costs nothing here and is the whole difference from
     * {@code field_not_allowed}: {@code neo_schema} already published this field with
     * {@code readOnly: true}, so the refusal repeats what the caller was told.
     */
    @Test
    @DisplayName("it names the reason, unlike the exclusion refusal")
    void readOnlyNamesTheReason() throws Exception {
      declareProperty("documentNo", "DocumentNo");
      curate("DocumentNo", Boolean.TRUE, Boolean.TRUE, null, null);

      McpRoutingException thrown = assertThrows(McpRoutingException.class,
          () -> write(fields("documentNo", "SO-9999"), specEntity()));
      String envelope = thrown.toEnvelope().toString().toLowerCase(Locale.ROOT);

      assertTrue(envelope.contains("read-only") || envelope.contains("readonly"),
          "an agent told only 'not allowed' cannot tell this apart from a field that does not"
              + " exist, and this one it CAN fix by dropping the key");
      assertTrue(envelope.contains("neo_schema"), "the hint must name where the flag is published");
    }

    /**
     * The exemption that was kept: an agent following the documented
     * {@code neo_defaults} → {@code neo_create} sequence echoes resolved values back, and being
     * refused for it would punish the recommended shape.
     */
    @Test
    @DisplayName("echoing back a literal AD default is accepted")
    void echoingTheConfiguredDefaultIsAccepted() throws Exception {
      declareProperty("documentStatus", "DocStatus");
      curate("DocStatus", Boolean.TRUE, Boolean.TRUE, "DR", null);

      assertEquals("DR",
          write(fields("documentStatus", "DR"), specEntity()).getString("documentStatus"));
    }

    /**
     * IMP-30's second half. The exemption used to cover any value at all, so
     * {@code documentStatus} (default {@code 'DR'}) accepted {@code "CO"} and created a completed
     * order with no lines — the exact state the 2026-08-13 probe reached. An echo is an echo; a
     * different value is an override of a field the surface publishes as read-only.
     */
    @Test
    @DisplayName("overriding a literal AD default with a different value is refused")
    void overridingTheConfiguredDefaultIsRefused() throws Exception {
      declareProperty("documentStatus", "DocStatus");
      curate("DocStatus", Boolean.TRUE, Boolean.TRUE, "DR", null);

      McpRoutingException thrown = assertThrows(McpRoutingException.class,
          () -> write(fields("documentStatus", "CO"), specEntity()));
      assertEquals(McpConstants.ERROR_READ_ONLY_FIELD,
          thrown.toEnvelope().getString(McpConstants.KEY_ERROR));
    }

    /**
     * A read-only column whose AD default is a context expression ({@code @#AD_User_ID@}) or a
     * function ({@code now()}) is refused outright: {@code literalDefault} returns {@code null}
     * for both, and {@code writeGate}'s {@code null} branch is the one that adds the property to
     * {@code readOnlyRejectable}.
     *
     * <p>The fixture deliberately avoids {@code AD_Org_ID}, which this test used to be written
     * against. {@code organization} is now discarded by {@code NeoServerOwnedFields} <em>before</em>
     * either gate runs, so it can no longer stand in for an ordinary read-only column here — the
     * discard is pinned in {@code ServerOwnedFields#readOnlyDoesNotTurnIntoARefusal} instead.</p>
     *
     * <p><b>This pins what the code does, and it is NOT what the code says it does.</b>
     * {@code literalDefault}'s own javadoc states that these columns <em>"keep the blanket
     * exemption they have had since IMP-48 — narrowing an exemption we cannot evaluate would
     * refuse legitimate echoes with no way for the caller to tell why"</em>. Under IMP-48 the
     * predicate was {@code isReadOnly() && !hasConfiguredDefault(col)}, so any configured default
     * exempted the column; after the IMP-30 narrowing an expression default exempts nothing. The
     * contradiction is reported rather than fixed here — a test may not decide which of the two
     * the product wants. Whichever wins, one of them has to move, and this assertion is the
     * tripwire that will notice.</p>
     */
    @Test
    @DisplayName("a non-literal AD default does NOT exempt today, contradicting literalDefault's "
        + "own javadoc")
    void expressionDefaultsAreRefusedToday() throws Exception {
      declareProperty("businessPartner", "C_BPartner_ID");
      declareProperty("orderDate", "DateOrdered");
      curate("C_BPartner_ID", Boolean.TRUE, Boolean.TRUE, "@#AD_User_ID@", null);
      curate("DateOrdered", Boolean.TRUE, Boolean.TRUE, "now()", null);
      SFEntity sfEntity = specEntity();

      for (String field : List.of("businessPartner", "orderDate")) {
        McpRoutingException thrown = assertThrows(McpRoutingException.class,
            () -> write(fields(field, "anything"), sfEntity),
            field + " is now exempt again — if that was deliberate, this test and"
                + " literalDefault's javadoc agree again and both should say so");
        assertEquals(McpConstants.ERROR_READ_ONLY_FIELD,
            thrown.toEnvelope().getString(McpConstants.KEY_ERROR));
      }
    }

    /**
     * The other half of the same contradiction, isolated: {@code hasConfiguredDefault} — the
     * IMP-48 predicate — no longer gates anything on its own, so a configured default only exempts
     * when it is a literal.
     */
    @Test
    @DisplayName("only a literal default exempts; a configured one no longer does by itself")
    void onlyLiteralDefaultsExempt() throws Exception {
      declareProperty("documentStatus", "DocStatus");
      declareProperty("businessPartner", "C_BPartner_ID");
      curate("DocStatus", Boolean.TRUE, Boolean.TRUE, "DR", null);
      curate("C_BPartner_ID", Boolean.TRUE, Boolean.TRUE, "@#AD_User_ID@", null);
      SFEntity sfEntity = specEntity();

      assertEquals("DR",
          write(fields("documentStatus", "DR"), sfEntity).getString("documentStatus"),
          "a literal default may still be echoed back");
      assertThrows(McpRoutingException.class,
          () -> write(fields("businessPartner", "BP-7"), sfEntity),
          "an expression default may not, which is the behaviour change IMP-30 brought with it");
    }

    /**
     * <b>The exemption that was dropped, and the reason it had to be.</b> On the REST path
     * {@code filterCreateRequest} runs after the handler pre-hook, so it cannot tell an injected
     * value from a client's and exempts the whole entity. Here the mapping runs on the caller's
     * own {@code fields} argument and the pre-hook fires downstream, so every key is the caller's
     * by construction. Keeping the exemption left the gate firing on under two fifths of the
     * surface, and a live probe caught {@code neo_update} on {@code sales-order/header} —
     * qualifier {@code salesOrderHeaderHandler} — accepting {@code documentNo} with a 200.
     */
    @Test
    @DisplayName("an entity with a Java_Qualifier is NOT exempt from the read-only gate")
    void theHandlerQualifierDoesNotExempt() throws Exception {
      declareProperty("documentNo", "DocumentNo");
      curate("DocumentNo", Boolean.TRUE, Boolean.TRUE, null, null);
      SFEntity sfEntity = specEntity();
      when(sfEntity.getJavaQualifier()).thenReturn("salesOrderHeaderHandler");

      McpRoutingException thrown = assertThrows(McpRoutingException.class,
          () -> write(fields("documentNo", "SO-9999"), sfEntity));
      assertEquals(McpConstants.ERROR_READ_ONLY_FIELD,
          thrown.toEnvelope().getString(McpConstants.KEY_ERROR));
    }

    /**
     * The behavioural test above can only prove the qualifier of <i>this</i> fixture is ignored.
     * The structural one proves the gate has no way to consult it at all, which is what stops the
     * exemption being quietly reinstated by a future edit.
     */
    @Test
    @DisplayName("writeGate reads no handler qualifier, so the exemption cannot creep back")
    void writeGateNeverConsultsTheQualifier() {
      String body = McpSourceScanner.methodBody(
          McpSourceScanner.read("com/etendoerp/go/mcp/McpQuerySupport.java"), "writeGate");
      assertFalse(body.contains("JavaQualifier"),
          "writeGate consults the entity's handler qualifier again. On the MCP path every key is"
              + " the caller's own, so the REST exemption has nothing to protect here and costs"
              + " 79 of the 128 writable entities their read-only gate.");
    }

    /** An editable field is the control: the gate must let the ordinary case through untouched. */
    @Test
    @DisplayName("an included, writable field is mapped and passed through")
    void theWritableCaseIsUntouched() throws Exception {
      declareProperty("businessPartner", "C_BPartner_ID");
      curate("C_BPartner_ID", Boolean.TRUE, Boolean.FALSE, null, null);

      JSONObject mapped = write(fields("C_BPartner_ID", "BP-1"), specEntity());
      assertEquals("BP-1", mapped.getString("businessPartner"),
          "the DB column spelling must be mapped onto the DAL property name");
    }

    /**
     * An {@code MCP_CONFIG} {@code fields.readOnly:false} reclaims a field for writing the same
     * way {@code included} reclaims an excluded one — the second half of routing the gate through
     * {@link McpFieldView}.
     */
    @Test
    @DisplayName("an MCP_CONFIG fields.readOnly:false reclaims a read-only field for writing")
    void theOverrideCanReclaimAReadOnlyField() throws Exception {
      declareProperty("documentNo", "DocumentNo");
      curate("DocumentNo", Boolean.TRUE, Boolean.TRUE, null,
          "{\"fields\":{\"readOnly\":false,\"reason\":\"write-gate fixture\"}}");

      assertEquals("SO-9999",
          write(fields("documentNo", "SO-9999"), specEntity()).getString("documentNo"));
    }
  }

  // ── both verbs ────────────────────────────────────────────────────────

  /**
   * Both write verbs must hand the spec entity to the mapping, and no signature can require it —
   * the two-argument overload compiles perfectly well and silently skips both gates. That is the
   * shape {@code handleUpdate} had before ETP-5335, and it is the shape
   * {@code McpWriteVerbCoercionCallSiteTest} already caught once for the date coercer.
   */
  @Nested
  @DisplayName("the gates are on both write verbs, not just create")
  class BothVerbs {

    @Test
    @DisplayName("handleCreate and handleUpdate both pass sfEntity into the mapping")
    void bothVerbsPassTheSpecEntity() {
      String source = McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java");
      for (String verb : List.of("handleCreate", "handleUpdate")) {
        String body = McpSourceScanner.methodBody(source, verb);
        assertTrue(body.contains("mapFieldsToDalProperties"),
            verb + " no longer maps its fields at all — fix this test only after checking why");
        assertTrue(body.matches("(?s).*mapFieldsToDalProperties\\s*\\([^)]*sfEntity[^)]*\\).*"),
            verb + " calls mapFieldsToDalProperties without sfEntity, which silently disables"
                + " both write gates: an excluded or read-only field is accepted again and the"
                + " write verbs stop agreeing with neo_schema about which fields exist.");
      }
    }
  }

  // ── unknownFields (IMP-18) ────────────────────────────────────────────

  /**
   * The write verbs <b>report</b> an unrecognised key; they do not refuse it. The symmetry with
   * the two gates was measured and rejected: at least eight of the 73 handler qualifiers reachable
   * by an MCP write read request keys that are not AD columns anywhere in the instance
   * ({@code formState}, {@code lines}, {@code shipmentId}, …), and "unknown" is not a set anything
   * declares — so a refusal could not tell a caller's typo from a handler's own protocol.
   */
  @Nested
  @DisplayName("unknownFields — reported, never refused")
  class UnknownFields {

    @Test
    @DisplayName("an unmapped key is collected and the write still carries it through")
    void anUnmappedKeyIsCollected() throws Exception {
      declareProperty("businessPartner", "C_BPartner_ID");
      Set<String> unknown = new TreeSet<>();
      JSONObject body = new JSONObject();
      body.put("C_BPartner_ID", "BP-1");
      body.put("busnessPartner", "typo");

      JSONObject mapped = McpWriteRequestSupport.mapFieldsToDalProperties(body, adTab,
          specEntity(), unknown);

      assertEquals(Set.of("busnessPartner"), unknown);
      assertEquals("typo", mapped.getString("busnessPartner"),
          "the key travels on — a NeoHandler may be its real consumer");
    }

    @Test
    @DisplayName("a clean write collects nothing and adds no key to the response")
    void aCleanWriteAddsNoKey() throws Exception {
      declareProperty("businessPartner", "C_BPartner_ID");
      Set<String> unknown = new TreeSet<>();

      McpWriteRequestSupport.mapFieldsToDalProperties(fields("C_BPartner_ID", "BP-1"), adTab,
          specEntity(), unknown);
      assertTrue(unknown.isEmpty());

      JSONObject response = new JSONObject();
      response.put("id", "rec-1");
      McpWriteRequestSupport.reportUnknownFields(response, unknown);
      assertFalse(response.has(McpFieldProjection.KEY_UNKNOWN_FIELDS),
          "a warning on a clean write is noise the agent has to rule out every time");
      assertFalse(response.has("unknownFieldsHint"));
    }

    /**
     * {@code parentId} is a declared argument of both write tools — {@code resolveParentFK}
     * consumes it — so reporting it would fire on the documented way to create a line.
     */
    @Test
    @DisplayName("parentId is never reported, on any entity")
    void parentIdIsNeverReported() throws Exception {
      declareProperty("product", "M_Product_ID");
      Set<String> unknown = new TreeSet<>();
      JSONObject body = new JSONObject();
      body.put("M_Product_ID", "P-1");
      body.put(McpConstants.PARAM_PARENT_ID, "ORD-1");

      McpWriteRequestSupport.mapFieldsToDalProperties(body, adTab, specEntity(), unknown);

      assertTrue(unknown.isEmpty(), "parentId is the documented way to create a line, not a typo");
    }

    @Test
    @DisplayName("the report carries the names and a hint naming the tool that lists them")
    void theReportIsActionable() throws Exception {
      JSONObject response = new JSONObject();
      McpWriteRequestSupport.reportUnknownFields(response, new TreeSet<>(List.of("a", "b")));

      assertEquals(2, response.getJSONArray(McpFieldProjection.KEY_UNKNOWN_FIELDS).length());
      String hint = response.getString("unknownFieldsHint");
      assertTrue(hint.contains("neo_schema"));
      assertTrue(hint.contains("create"),
          "the hint must name the projection to ask for, now that view is required");
    }

    /**
     * The hint has to stay true for the eight-odd entities whose handlers read their own request
     * keys: claiming the value was ignored would be a lie there, and an agent that believes it
     * would retry a write that already worked.
     */
    @Test
    @DisplayName("the hint does not claim the value was ignored")
    void theHintDoesNotOverclaim() throws Exception {
      JSONObject response = new JSONObject();
      McpWriteRequestSupport.reportUnknownFields(response, new TreeSet<>(List.of("formState")));

      String hint = response.getString("unknownFieldsHint").toLowerCase(Locale.ROOT);
      assertFalse(hint.contains("ignored"),
          "a NeoHandler may have consumed the key; 'ignored' would be false on those entities");
    }

    @Test
    @DisplayName("a null body or an empty set is a no-op rather than a failure")
    void reportingIsNeverFatal() {
      McpWriteRequestSupport.reportUnknownFields(null, new TreeSet<>(List.of("a")));
      JSONObject response = new JSONObject();
      McpWriteRequestSupport.reportUnknownFields(response, null);
      assertEquals(0, response.length());
    }
  }

  // ── tenant ownership ──────────────────────────────────────────────────

  /**
   * {@code client} and {@code organization} are resolved from the session on every write.
   *
   * <p><b>The defect.</b> A {@code neo_create} carrying another org's {@code organization}
   * answered {@code 200 OK} and the record was then invisible to the session that created it — a
   * {@code 404} on the id the response had just returned, because the row went into the other
   * tenant. Neither column has an {@code ETGO_SF_FIELD} row, and both gates above are built
   * entirely out of those rows, so the key matched neither deny-set and reached
   * {@code jsonService.add} untouched. Curation could not have caught this: the gates read
   * <em>decisions</em>, and nobody had made one about a column nobody curates.</p>
   *
   * <p>The rule therefore runs <b>before</b> both gates and discards rather than refuses — which
   * is asserted here, because a 422 would be a behaviour change for every client that echoes a
   * record back, and silence-plus-report is what was chosen instead.</p>
   */
  @Nested
  @DisplayName("client and organization never reach the body")
  class ServerOwnedFields {

    /** Declare the two tenant columns the way the real DAL model maps them. */
    private void declareTenantProperties() {
      declareProperty("organization", "AD_Org_ID");
      declareProperty("client", "AD_Client_ID");
    }

    /**
     * Per spelling, each in a body of its own. A single body asserting "none of the four
     * survived" would pass while three are handled and the fourth is carried out by one of the
     * others; and the crossed-wires bug (a fallback sending {@code ad_client_id} to
     * {@code organization}) is invisible unless each key is judged alone.
     */
    @ParameterizedTest
    @ValueSource(strings = { "organization", "client", "AD_Org_ID", "AD_Client_ID" })
    @DisplayName("every spelling is discarded before it can reach the DAL")
    void everySpellingIsDiscarded(String key) throws Exception {
      declareTenantProperties();

      JSONObject mapped = write(fields(key, "SOMEONE-ELSE"), specEntity());

      assertEquals(0, mapped.length(),
          key + " reached the mapped body — this is the cross-tenant write, spelled " + key);
    }

    /**
     * <b>Pins what the code does, which is NOT what the policy's key map intends.</b> On the MCP
     * path the check is {@code isServerOwned(mappedKey)}, and {@code mappedKey} is only rewritten
     * when the DAL resolves the key — an {@code $_identifier} companion resolves to no property,
     * so it keeps its own spelling, is not server-owned, and travels into the mapped body (and
     * into {@code unknownFields}). {@code NeoServerOwnedFields.isServerOwnedKey} — which the REST
     * path uses — does list an identifier variant, so the two write paths disagree about this
     * key. Reported, not fixed; see also the suffix finding in
     * {@code NeoServerOwnedFieldsTest#theRealIdentifierCompanionIsNotRecognised}.
     */
    @ParameterizedTest
    @ValueSource(strings = { "organization$_identifier", "organization_identifier",
        "client$_identifier", "client_identifier" })
    @DisplayName("an identifier companion is NOT discarded on the MCP path today")
    void identifierCompanionsSurviveOnTheMcpPath(String key) throws Exception {
      declareTenantProperties();

      JSONObject mapped = write(fields(key, "SOMEONE-ELSE"), specEntity());

      assertTrue(mapped.has(key),
          "if this starts failing the MCP path learned the companion spellings — good; update"
              + " this test to assert the discard and drop the finding");
    }

    /**
     * Discarding is unconditional. The comparison decides only whether the caller is told — a
     * rule that kept the value when it matched would still be reading tenant identity out of the
     * payload, and would be probeable.
     */
    @Test
    @DisplayName("a value equal to the session's is discarded just the same")
    void anEchoIsDiscardedToo() throws Exception {
      declareTenantProperties();
      Client sessionClient = mock(Client.class);
      when(sessionClient.getId()).thenReturn("CLIENT-SESSION");
      Organization sessionOrg = mock(Organization.class);
      when(sessionOrg.getId()).thenReturn("ORG-SESSION");
      OBContext context = mock(OBContext.class);
      when(context.getCurrentClient()).thenReturn(sessionClient);
      when(context.getCurrentOrganization()).thenReturn(sessionOrg);

      try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
        obContext.when(OBContext::getOBContext).thenReturn(context);
        JSONObject serverOwned = new JSONObject();

        JSONObject mapped = write(fields("organization", "ORG-SESSION"), specEntity(), serverOwned);

        assertEquals(0, mapped.length(), "the session decides, so the echo is redundant, not kept");
        assertEquals(0, serverOwned.length(),
            "nothing was taken from the caller, so it is told nothing");
      }
    }

    @Test
    @DisplayName("a different tenant is reported with what was sent and where it really went")
    void aDifferentTenantIsReported() throws Exception {
      declareTenantProperties();
      Organization sessionOrg = mock(Organization.class);
      when(sessionOrg.getId()).thenReturn("ORG-SESSION");
      OBContext context = mock(OBContext.class);
      when(context.getCurrentOrganization()).thenReturn(sessionOrg);

      try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
        obContext.when(OBContext::getOBContext).thenReturn(context);
        JSONObject serverOwned = new JSONObject();

        write(fields("organization", "ORG-ELSEWHERE"), specEntity(), serverOwned);

        assertEquals("ORG-ELSEWHERE",
            serverOwned.getJSONObject("organization").getString("sent"));
        assertEquals("ORG-SESSION",
            serverOwned.getJSONObject("organization").getString("session"),
            "the caller must learn where the record actually landed, not just that it was"
                + " ignored — a 404 on its own id was the previous way to find out");
      }
    }

    /**
     * <b>The rule runs before both gates, and that ordering is the behaviour.</b> If it ran after,
     * a curated-out or read-only tenant column would answer 422 instead of being discarded — a
     * refusal for a key every client that echoes a record back would be sending.
     */
    @Test
    @DisplayName("an excluded tenant column is discarded in silence, not refused")
    void exclusionDoesNotTurnIntoARefusal() throws Exception {
      declareTenantProperties();
      curate("AD_Org_ID", Boolean.FALSE, Boolean.FALSE, null, null);

      JSONObject mapped = write(fields("organization", "ORG-ELSEWHERE"), specEntity());

      assertEquals(0, mapped.length(),
          "discarded, and with no field_not_allowed thrown on the way");
    }

    @Test
    @DisplayName("a read-only tenant column is discarded in silence, not refused")
    void readOnlyDoesNotTurnIntoARefusal() throws Exception {
      declareTenantProperties();
      curate("AD_Org_ID", Boolean.TRUE, Boolean.TRUE, null, null);

      JSONObject mapped = write(fields("organization", "ORG-ELSEWHERE"), specEntity());

      assertEquals(0, mapped.length(),
          "discarded, and with no read_only_field thrown on the way");
    }

    @Test
    @DisplayName("ordinary fields around the tenant keys are untouched")
    void theRestOfTheBodySurvives() throws Exception {
      declareTenantProperties();
      declareProperty("businessPartner", "C_BPartner_ID");
      JSONObject body = new JSONObject();
      body.put("C_BPartner_ID", "BP-1");
      body.put("organization", "ORG-ELSEWHERE");

      JSONObject mapped = write(body, specEntity());

      assertEquals(1, mapped.length());
      assertEquals("BP-1", mapped.getString("businessPartner"));
    }

    /**
     * The four-argument overload is still the one several tests above call, and it must apply the
     * same rule — an overload that skipped it would be a way back into the defect.
     */
    @Test
    @DisplayName("the four-argument overload applies the rule too")
    void theShorterOverloadStillApplies() throws Exception {
      declareTenantProperties();
      Set<String> unknown = new TreeSet<>();

      JSONObject mapped = McpWriteRequestSupport.mapFieldsToDalProperties(
          fields("organization", "ORG-ELSEWHERE"), adTab, specEntity(), unknown);

      assertEquals(0, mapped.length());
      assertTrue(unknown.isEmpty(),
          "a discarded tenant key is not an unrecognised name — reporting it as one would send"
              + " the caller looking for a typo");
    }

    @Test
    @DisplayName("with no SchemaForge entity the rule still applies")
    void theRuleIsNotGatedOnCuration() throws Exception {
      declareTenantProperties();

      assertEquals(0, write(fields("organization", "ORG-ELSEWHERE"), null).length(),
          "tenant ownership is not a curation decision, so a spec-less write must not opt out");
    }

    // ── the report on the response ──────────────────────────────────────

    @Test
    @DisplayName("the response carries the report and a hint that says not to send them")
    void theResponseCarriesTheReport() throws Exception {
      JSONObject report = new JSONObject().put("organization",
          new JSONObject().put("sent", "ORG-ELSEWHERE").put("session", "ORG-SESSION"));
      JSONObject body = new JSONObject().put("id", "ord-1");

      McpWriteRequestSupport.reportServerOwnedFields(body, report);

      assertEquals("ORG-ELSEWHERE",
          body.getJSONObject("serverOwnedFields").getJSONObject("organization").getString("sent"));
      String hint = body.getString("serverOwnedFieldsHint").toLowerCase(Locale.ROOT);
      assertTrue(hint.contains("session"), "the hint must say where the value came from");
      assertTrue(hint.contains("do not send"), "and that the remedy is to stop sending them");
      assertEquals("ord-1", body.getString("id"), "the record itself is untouched");
    }

    @Test
    @DisplayName("a write that sent no tenant field carries no key at all")
    void aCleanWriteCarriesNoKey() throws Exception {
      JSONObject body = new JSONObject().put("id", "ord-1");

      McpWriteRequestSupport.reportServerOwnedFields(body, new JSONObject());
      McpWriteRequestSupport.reportServerOwnedFields(body, null);
      McpWriteRequestSupport.reportServerOwnedFields(null, new JSONObject());

      assertFalse(body.has("serverOwnedFields"));
      assertFalse(body.has("serverOwnedFieldsHint"));
      assertEquals(1, body.length());
    }

    /**
     * Both verbs, at the call site. Nothing in a signature requires the five-argument overload —
     * the four-argument one compiles and silently drops the report — and an update that moved
     * {@code organization} would relocate an existing record, which is the same hole from the
     * other direction.
     */
    @Test
    @DisplayName("handleCreate and handleUpdate both collect and both report")
    void bothVerbsCollectAndReport() {
      String source = McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java");
      for (String verb : List.of("handleCreate", "handleUpdate")) {
        String body = McpSourceScanner.methodBody(source, verb);
        assertTrue(body.matches(
            "(?s).*mapFieldsToDalProperties\\s*\\([^)]*serverOwnedFields[^)]*\\).*"),
            verb + " does not pass a serverOwnedFields collector, so a caller that sent another"
                + " tenant is told nothing — the 404-on-your-own-record failure again");
        assertTrue(body.contains("reportServerOwnedFields"),
            verb + " collects the report and never attaches it to the response");
      }
    }
  }
}
