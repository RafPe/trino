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
package io.trino.plugin.memoryhistory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;

final class TestMemoryHistoryConfig
{
    @Test
    void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(MemoryHistoryConfig.class)
                .setUrl(null)
                .setUser(null)
                .setPassword(null));
    }

    @Test
    void testExplicitPropertyMappings()
    {
        Map<String, String> properties = Map.of(
                "memory-history.db.url", "jdbc:postgresql://example.net:5432/history",
                "memory-history.db.user", "trino",
                "memory-history.db.password", "secret");

        MemoryHistoryConfig expected = new MemoryHistoryConfig()
                .setUrl("jdbc:postgresql://example.net:5432/history")
                .setUser("trino")
                .setPassword("secret");

        assertFullMapping(properties, expected);
    }
}
