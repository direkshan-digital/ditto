/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.signals.commands.things.modify;

import static org.eclipse.ditto.signals.commands.things.TestConstants.Pointer.INVALID_JSON_POINTER;
import static org.eclipse.ditto.signals.commands.things.TestConstants.Pointer.VALID_JSON_POINTER;
import static org.eclipse.ditto.signals.commands.things.assertions.ThingCommandAssertions.assertThat;
import static org.mutabilitydetector.unittesting.AllowedReason.provided;
import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonKeyInvalidException;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.json.FieldType;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.signals.commands.things.TestConstants;
import org.eclipse.ditto.signals.commands.things.ThingCommandResponse;
import org.junit.Test;

import nl.jqno.equalsverifier.EqualsVerifier;

/**
 * Unit test for {@link ModifyFeatureDesiredPropertyResponse}.
 */
public class ModifyFeatureDesiredPropertyResponseTest {

    private static final JsonObject KNOWN_JSON_CREATED = JsonFactory.newObjectBuilder()
            .set(ThingCommandResponse.JsonFields.TYPE, ModifyFeatureDesiredPropertyResponse.TYPE)
            .set(ThingCommandResponse.JsonFields.STATUS, HttpStatusCode.CREATED.toInt())
            .set(ThingCommandResponse.JsonFields.JSON_THING_ID, TestConstants.Thing.THING_ID.toString())
            .set(ModifyFeatureDesiredPropertyResponse.JSON_FEATURE_ID, TestConstants.Feature.HOVER_BOARD_ID)
            .set(ModifyFeatureDesiredPropertyResponse.JSON_DESIRED_PROPERTY,
                    TestConstants.Feature.HOVER_BOARD_DESIRED_PROPERTY_POINTER.toString())
            .set(ModifyFeatureDesiredPropertyResponse.JSON_DESIRED_VALUE,
                    TestConstants.Feature.HOVER_BOARD_DESIRED_PROPERTY_VALUE)
            .build();

    private static final JsonObject KNOWN_JSON_UPDATED = JsonFactory.newObjectBuilder()
            .set(ThingCommandResponse.JsonFields.TYPE, ModifyFeatureDesiredPropertyResponse.TYPE)
            .set(ThingCommandResponse.JsonFields.STATUS, HttpStatusCode.NO_CONTENT.toInt())
            .set(ThingCommandResponse.JsonFields.JSON_THING_ID, TestConstants.Thing.THING_ID.toString())
            .set(ModifyFeatureDesiredPropertiesResponse.JSON_FEATURE_ID, TestConstants.Feature.HOVER_BOARD_ID)
            .set(ModifyFeatureDesiredPropertyResponse.JSON_DESIRED_PROPERTY,
                    TestConstants.Feature.HOVER_BOARD_DESIRED_PROPERTY_POINTER.toString())
            .build();

    @Test
    public void assertImmutability() {
        assertInstancesOf(ModifyFeatureDesiredPropertyResponse.class,
                areImmutable(),
                provided(JsonValue.class, JsonPointer.class, ThingId.class).isAlsoImmutable());
    }

    @Test
    public void testHashCodeAndEquals() {
        EqualsVerifier.forClass(ModifyFeatureDesiredPropertyResponse.class)
                .withRedefinedSuperclass()
                .verify();
    }

    @Test
    public void toJsonReturnsExpected() {
        final ModifyFeatureDesiredPropertyResponse underTestCreated = ModifyFeatureDesiredPropertyResponse.created(
                TestConstants.Thing.THING_ID,
                TestConstants.Feature.HOVER_BOARD_ID, TestConstants.Feature.HOVER_BOARD_DESIRED_PROPERTY_POINTER,
                TestConstants.Feature.HOVER_BOARD_DESIRED_PROPERTY_VALUE, TestConstants.EMPTY_DITTO_HEADERS);
        final JsonObject actualJsonCreated = underTestCreated.toJson(FieldType.regularOrSpecial());

        assertThat(actualJsonCreated).isEqualTo(KNOWN_JSON_CREATED);

        final ModifyFeatureDesiredPropertyResponse underTestUpdated =
                ModifyFeatureDesiredPropertyResponse.modified(TestConstants.Thing.THING_ID,
                        TestConstants.Feature.HOVER_BOARD_ID,
                        TestConstants.Feature.HOVER_BOARD_DESIRED_PROPERTY_POINTER, TestConstants.EMPTY_DITTO_HEADERS);
        final JsonObject actualJsonUpdated = underTestUpdated.toJson(FieldType.regularOrSpecial());

        assertThat(actualJsonUpdated).isEqualTo(KNOWN_JSON_UPDATED);
    }

    @Test
    public void createInstanceFromValidJson() {
        final ModifyFeatureDesiredPropertyResponse underTestCreated =
                ModifyFeatureDesiredPropertyResponse.fromJson(KNOWN_JSON_CREATED, TestConstants.EMPTY_DITTO_HEADERS);

        assertThat(underTestCreated).isNotNull();
        assertThat(underTestCreated.getDesiredPropertyValue())
                .hasValue(TestConstants.Feature.HOVER_BOARD_DESIRED_PROPERTY_VALUE);

        final ModifyFeatureDesiredPropertyResponse underTestUpdated =
                ModifyFeatureDesiredPropertyResponse.fromJson(KNOWN_JSON_UPDATED, TestConstants.EMPTY_DITTO_HEADERS);

        assertThat(underTestUpdated).isNotNull();
        assertThat(underTestUpdated.getDesiredPropertyValue()).isEmpty();
    }

    @Test
    public void tryToCreateInstanceWithValidArguments() {
        ModifyFeatureDesiredPropertyResponse.modified(TestConstants.Thing.THING_ID,
                TestConstants.Feature.HOVER_BOARD_ID,
                VALID_JSON_POINTER, TestConstants.EMPTY_DITTO_HEADERS);
    }

    @Test(expected = JsonKeyInvalidException.class)
    public void tryToCreateInstanceWithInvalidArguments() {
        ModifyFeatureDesiredPropertyResponse.modified(TestConstants.Thing.THING_ID,
                TestConstants.Feature.HOVER_BOARD_ID,
                INVALID_JSON_POINTER, TestConstants.EMPTY_DITTO_HEADERS);
    }
}
