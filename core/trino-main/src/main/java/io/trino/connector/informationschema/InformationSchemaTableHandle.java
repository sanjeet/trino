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
package io.trino.connector.informationschema;

import com.google.common.collect.ImmutableSet;
import io.trino.metadata.QualifiedTablePrefix;
import io.trino.spi.connector.ConnectorTableHandle;

import java.util.OptionalLong;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public record InformationSchemaTableHandle(String catalogName, InformationSchemaTable table, Set<QualifiedTablePrefix> prefixes, OptionalLong limit)
        implements ConnectorTableHandle
{
    public InformationSchemaTableHandle
    {
        requireNonNull(catalogName, "catalogName is null");
        requireNonNull(table, "table is null");
        prefixes = ImmutableSet.copyOf(requireNonNull(prefixes, "prefixes is null"));
        requireNonNull(limit, "limit is null");
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("catalog=").append(catalogName);
        builder.append(" table=").append(table);
        if (!prefixes.isEmpty()) {
            builder.append(" prefixes=").append(prefixes);
        }
        limit.ifPresent(limit -> builder.append(" limit=").append(limit));
        return builder.toString();
    }
}
