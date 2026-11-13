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
package io.trino.memoryhistory.predictor;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.InstantSource;
import java.util.logging.Logger;

public final class PredictorMain
{
    private static final Logger log = Logger.getLogger(PredictorMain.class.getName());

    private PredictorMain() {}

    public static void main(String[] args)
            throws Exception
    {
        PredictorConfig config = PredictorConfig.fromEnvironment(System.getenv());
        HistoryRepository repository = new HistoryRepository(config);
        Reservations reservations = new Reservations(config, InstantSource.system());
        PredictorServer server = new PredictorServer(
                new PredictorService(config, repository),
                reservations,
                new InetSocketAddress(config.port()));
        AutoRelease autoRelease = new AutoRelease(
                repository::completedSince,
                reservations,
                Duration.ofMillis(config.autoReleaseIntervalMs()),
                AutoRelease.DEFAULT_OVERLAP,
                InstantSource.system().instant());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            autoRelease.close();
            server.close();
        }));
        server.start();
        autoRelease.start();
        log.info("Memory history predictor listening on port %s (per-worker memory %s bytes, workers %s-%s)".formatted(
                server.port(), config.perWorkerMemoryBytes(), config.minWorkers(), config.maxWorkers()));
    }
}
