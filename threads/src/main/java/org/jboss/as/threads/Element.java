/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.threads;

import java.util.HashMap;
import java.util.Map;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public enum Element {
    // must be first
    UNKNOWN(null),

    // Threads 1.0 elements in alpha order
    BLOCKING_BOUNDED_QUEUE_THREAD_POOL(CommonAttributes.BLOCKING_BOUNDED_QUEUE_THREAD_POOL),
    BLOCKING_QUEUELESS_THREAD_POOL(CommonAttributes.BLOCKING_QUEUELESS_THREAD_POOL),
    BOUNDED_QUEUE_THREAD_POOL(CommonAttributes.BOUNDED_QUEUE_THREAD_POOL),
    CORE_THREADS(CommonAttributes.CORE_THREADS),
    HANDOFF_EXECUTOR(CommonAttributes.HANDOFF_EXECUTOR),
    KEEPALIVE_TIME(CommonAttributes.KEEPALIVE_TIME),
    MAX_THREADS(CommonAttributes.MAX_THREADS),
    PROPERTIES(CommonAttributes.PROPERTIES),
    PROPERTY(CommonAttributes.PROPERTY),
    QUEUE_LENGTH(CommonAttributes.QUEUE_LENGTH),
    QUEUELESS_THREAD_POOL(CommonAttributes.QUEUELESS_THREAD_POOL),
    SCHEDULED_THREAD_POOL(CommonAttributes.SCHEDULED_THREAD_POOL),
    SUBSYSTEM(org.jboss.as.controller.parsing.Element.SUBSYSTEM.getLocalName()),
    THREAD_FACTORY(CommonAttributes.THREAD_FACTORY),
    UNBOUNDED_QUEUE_THREAD_POOL(CommonAttributes.UNBOUNDED_QUEUE_THREAD_POOL),
    ;

    private final String name;

    Element(final String name) {
        this.name = name;
    }

    /**
     * Get the local name of this element.
     *
     * @return the local name
     */
    public String getLocalName() {
        return name;
    }

    private static final Map<String, Element> MAP;

    static {
        final Map<String, Element> map = new HashMap<>();
        for (Element element : values()) {
            final String name = element.getLocalName();
            if (name != null) map.put(name, element);
        }
        MAP = map;
    }

    public static Element forName(String localName) {
        final Element element = MAP.get(localName);
        return element == null ? UNKNOWN : element;
    }

}
