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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * Unit tests for {@link InOutFollowUpCreator} (ETP-5576): the reusable "→ goods movement"
 * creator only composes its source mapper with {@link InOutTargetBuilder#build} — the mapping is
 * resolved first, the builder gets the creator's direction with the mapper's header and lines and
 * the creator's linker, and the result describes the persisted movement.
 *
 * @covers com.etendoerp.go.schemaforge.InOutFollowUpCreator
 */
class InOutFollowUpCreatorTest {

  private static final List<PendingResolver.SourceLine> PENDING = Collections.singletonList(
      new PendingResolver.SourceLine("src-line-1", BigDecimal.ONE));

  private MockedStatic<InOutTargetBuilder> builderStatic;
  private InOutFollowUpCreator.SourceMapper mapper;
  private InOutTargetBuilder.LineLinker linker;

  @BeforeEach
  void setUp() {
    builderStatic = mockStatic(InOutTargetBuilder.class);
    mapper = mock(InOutFollowUpCreator.SourceMapper.class);
    linker = mock(InOutTargetBuilder.LineLinker.class);
  }

  @AfterEach
  void tearDown() {
    builderStatic.close();
  }

  @Test
  void aSourceTheMapperCannotFindIsNotFoundAndNothingIsBuilt() {
    when(mapper.map("src-gone", PENDING))
        .thenThrow(new FollowUpException(FollowUpException.Reason.NOT_FOUND));
    InOutFollowUpCreator creator =
        new InOutFollowUpCreator(InOutTargetBuilder.Direction.SALES, mapper, linker);

    FollowUpException e = assertThrows(FollowUpException.class,
        () -> creator.createTarget("src-gone", PENDING));

    assertEquals(FollowUpException.Reason.NOT_FOUND, e.getReason());
    builderStatic.verifyNoInteractions();
  }

  @ParameterizedTest
  @EnumSource(InOutTargetBuilder.Direction.class)
  void buildsInItsDirectionFromTheMappingAndDescribesTheCreatedMovement(
      InOutTargetBuilder.Direction direction) {
    InOutTargetBuilder.Header header = new InOutTargetBuilder.Header(null, null, null, null, null,
        null, null);
    List<InOutTargetBuilder.Line> lines = Arrays.asList(line("src-line-1"), line("src-line-2"));
    InOutFollowUpCreator.Mapping mapping = new InOutFollowUpCreator.Mapping(header, lines);
    when(mapper.map("src-1", PENDING)).thenReturn(mapping);
    ShipmentInOut inout = mock(ShipmentInOut.class);
    when(inout.getId()).thenReturn("io-1");
    when(inout.getDocumentNo()).thenReturn("DOC-0001");
    builderStatic.when(() -> InOutTargetBuilder.build(any(), any(), any(), any()))
        .thenReturn(inout);
    InOutFollowUpCreator creator = new InOutFollowUpCreator(direction, mapper, linker);

    TargetCreator.Result result = creator.createTarget("src-1", PENDING);

    builderStatic.verify(() -> InOutTargetBuilder.build(direction, header, mapping.getLines(),
        linker));
    assertEquals("io-1", result.getId());
    assertEquals("DOC-0001", result.getDocumentNo());
    assertEquals(2, result.getLineCount());
  }

  private static InOutTargetBuilder.Line line(String sourceLineId) {
    return InOutTargetBuilder.Line.builder().sourceLineId(sourceLineId).quantity(BigDecimal.ONE)
        .stockable(false).build();
  }
}
