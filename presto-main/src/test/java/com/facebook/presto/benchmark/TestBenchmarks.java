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
package com.facebook.presto.benchmark;

import com.facebook.presto.util.InMemoryTpchBlocksProvider;
import org.testng.annotations.Test;

import java.util.concurrent.ExecutorService;

import static com.facebook.presto.util.Threads.daemonThreadsNamed;
import static java.util.concurrent.Executors.newCachedThreadPool;

public class TestBenchmarks
{
    @Test
    public void smokeTest()
            throws Exception
    {
        ExecutorService executor = newCachedThreadPool(daemonThreadsNamed("test"));
        try {
            for (AbstractBenchmark benchmark : BenchmarkSuite.createBenchmarks(executor, new InMemoryTpchBlocksProvider())) {
                try {
                    benchmark.runOnce();
                }
                catch (Exception e) {
                    throw new AssertionError("Error running " + benchmark.getBenchmarkName(), e);
                }
            }
        }
        finally {
            executor.shutdownNow();
        }
    }
}
