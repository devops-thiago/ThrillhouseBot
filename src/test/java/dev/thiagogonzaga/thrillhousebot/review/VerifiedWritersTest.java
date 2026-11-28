/*
 * Copyright 2026 Thiago Gonzaga
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.thiagogonzaga.thrillhousebot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerifiedWritersTest {

  @Test
  void aConfirmedLoginWithAWriteCapableAssociationIsAWriter() {
    var writers = VerifiedWriters.of(List.of("Keeper"));

    assertTrue(writers.isWriter("keeper", "MEMBER"));
    assertTrue(writers.isWriter(" KEEPER ", " collaborator "));
    assertTrue(writers.isWriter("keeper", "OWNER"));
  }

  @Test
  void anAssociationAloneNeverMakesAWriter() {
    var writers = VerifiedWriters.of(List.of("keeper"));

    assertFalse(writers.isWriter("sorter", "MEMBER"), "a member nobody confirmed");
    assertFalse(writers.isWriter("sorter", "COLLABORATOR"), "a collaborator nobody confirmed");
    assertFalse(writers.isWriter("sorter", "OWNER"), "an owner nobody confirmed");
    assertFalse(writers.isWriter(null, "MEMBER"), "a comment with no login");
  }

  @Test
  void aConfirmedLoginStillNeedsAWriteCapableAssociation() {
    var writers = VerifiedWriters.of(List.of("keeper"));

    assertFalse(writers.isWriter("keeper", "CONTRIBUTOR"));
    assertFalse(writers.isWriter("keeper", "NONE"));
    assertFalse(writers.isWriter("keeper", null));
  }

  @Test
  void nobodyIsAWriterWhenNobodyWasConfirmed() {
    assertFalse(VerifiedWriters.none().isWriter("keeper", "OWNER"));
    assertSame(VerifiedWriters.none(), VerifiedWriters.of(List.of()));
    assertSame(VerifiedWriters.none(), VerifiedWriters.of(Arrays.asList(null, "  ")));
  }

  @Test
  void twoSetsOfTheSameLoginsAreEqual() {
    var writers = VerifiedWriters.of(List.of("keeper", "Owner"));

    assertEquals(writers, VerifiedWriters.of(List.of("OWNER", "keeper")));
    assertEquals(writers.hashCode(), VerifiedWriters.of(List.of("owner", "keeper")).hashCode());
    assertNotEquals(writers, VerifiedWriters.none());
    assertThat(writers).isNotEqualTo("keeper");
    assertEquals("VerifiedWriters[keeper]", VerifiedWriters.of(List.of("keeper")).toString());
  }
}
