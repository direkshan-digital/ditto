/*
 * Copyright (c) 2017-2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.signals.events.base.assertions;

import org.eclipse.ditto.model.base.assertions.DittoBaseAssertions;
import org.eclipse.ditto.signals.events.base.Event;

/**
 * Custom assertions for commands.
 */
public class EventAssertions extends DittoBaseAssertions {

    /**
     * Returns an assert for an {@link Event}.
     *
     * @param event the event to be checked.
     * @return the Assert.
     */
    public static EventAssert assertThat(final Event<?> event) {
        return new EventAssert(event);
    }

}
