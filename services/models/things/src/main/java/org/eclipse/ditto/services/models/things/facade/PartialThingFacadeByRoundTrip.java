/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.models.things.facade;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.eclipse.ditto.json.JsonFieldSelector;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.json.JsonSchemaVersion;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.signals.commands.things.query.RetrieveThing;
import org.eclipse.ditto.signals.commands.things.query.RetrieveThingResponse;

import akka.actor.ActorRef;
import akka.pattern.Patterns;

/**
 * Retrieve fixed parts of things by asking an actor.
 */
final class PartialThingFacadeByRoundTrip implements PartialThingFacade {

    private final JsonFieldSelector jsonFieldSelector;
    private final ActorRef commandHandler;
    private final Duration askTimeout;

    PartialThingFacadeByRoundTrip(final JsonFieldSelector jsonFieldSelector,
            final ActorRef commandHandler, final Duration askTimeout) {
        this.jsonFieldSelector = jsonFieldSelector;
        this.commandHandler = commandHandler;
        this.askTimeout = askTimeout;
    }

    @Override
    public CompletionStage<JsonObject> retrievePartialThing(final ThingId thingId, final DittoHeaders dittoHeaders) {

        final RetrieveThing command =
                RetrieveThing.getBuilder(thingId, dittoHeaders).withSelectedFields(jsonFieldSelector).build();

        final CompletionStage<Object> askResult = Patterns.ask(commandHandler, command, askTimeout);

        return askResult.thenCompose(PartialThingFacadeByRoundTrip::extractPartialThing);
    }

    private static CompletionStage<JsonObject> extractPartialThing(final Object object) {
        if (object instanceof RetrieveThingResponse) {
            final RetrieveThingResponse retrieveThingResponse = (RetrieveThingResponse) object;
            return CompletableFuture.completedFuture(retrieveThingResponse.getEntity(JsonSchemaVersion.LATEST));
        } else {
            final CompletableFuture<JsonObject> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(toThrowable(object));
            return failedFuture;
        }
    }

    private static Throwable toThrowable(final Object object) {
        if (object instanceof Throwable) {
            return (Throwable) object;
        } else {
            return new IllegalStateException("Unexpected message: " + object);
        }
    }
}
