/*
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
package io.trino.plugin.openlineage;

import com.google.common.collect.ImmutableMap;
import io.trino.plugin.base.evenlistener.TestingEventListenerContext;
import io.trino.spi.eventlistener.EventListenerFactory;
import org.junit.jupiter.api.Test;

import static com.google.common.collect.Iterables.getOnlyElement;

final class TestOpenLineagePlugin
{
    @Test
    void testCreateConsoleEventListener()
    {
        OpenLineagePlugin plugin = new OpenLineagePlugin();

        EventListenerFactory factory = getOnlyElement(plugin.getEventListenerFactories());
        factory.create(
                        ImmutableMap.<String, String>builder()
                                .put("openlineage-event-listener.trino.uri", "http://localhost:8080")
                                .put("openlineage-event-listener.transport.type", "console")
                                .put("bootstrap.quiet", "true")
                                .buildOrThrow(), new TestingEventListenerContext())
                .shutdown();
    }

    @Test
    void testCreateHttpEventListener()
    {
        OpenLineagePlugin plugin = new OpenLineagePlugin();

        EventListenerFactory factory = getOnlyElement(plugin.getEventListenerFactories());
        factory.create(
                        ImmutableMap.<String, String>builder()
                                .put("openlineage-event-listener.trino.uri", "http://localhost:8080")
                                .put("openlineage-event-listener.transport.type", "http")
                                .put("openlineage-event-listener.transport.url", "http://testurl")
                                .put("bootstrap.quiet", "true")
                                .buildOrThrow(), new TestingEventListenerContext())
                .shutdown();
    }
}
