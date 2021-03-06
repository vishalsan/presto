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
package com.facebook.presto.server;

import com.facebook.presto.metadata.CatalogManager;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.inject.Injector;
import com.google.inject.Module;
import io.airlift.bootstrap.Bootstrap;
import io.airlift.discovery.client.Announcer;
import io.airlift.discovery.client.DiscoveryModule;
import io.airlift.event.client.HttpEventModule;
import io.airlift.event.client.JsonEventModule;
import io.airlift.floatingdecimal.FloatingDecimal;
import io.airlift.http.server.HttpServerModule;
import io.airlift.jaxrs.JaxrsModule;
import io.airlift.jmx.JmxHttpModule;
import io.airlift.jmx.JmxModule;
import io.airlift.json.JsonModule;
import io.airlift.log.LogJmxModule;
import io.airlift.log.Logger;
import io.airlift.node.NodeModule;
import io.airlift.tracetoken.TraceTokenModule;
import org.weakref.jmx.guice.MBeanModule;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.concurrent.atomic.AtomicBoolean;

public class PrestoServer
        implements Runnable
{
    private static final AtomicBoolean codeCacheTriggerInstalled = new AtomicBoolean();

    public static void main(String[] args)
    {
        new PrestoServer().run();
    }

    private static void installCodeCacheGcTrigger()
    {
        if (codeCacheTriggerInstalled.getAndSet(true)) {
            return;
        }

        // Hack to work around bugs in java 7 related to code cache management.
        // See http://mail.openjdk.java.net/pipermail/hotspot-compiler-dev/2013-August/011333.html for more info.
        final MemoryPoolMXBean codeCacheMbean = findCodeCacheMBean();
        Preconditions.checkNotNull(codeCacheMbean, "Could not obtain a reference to the 'Code Cache' MemoryPoolMXBean");

        Thread gcThread = new Thread(new Runnable()
        {
            @SuppressWarnings("CallToSystemGC")
            @Override
            public void run()
            {
                Logger log = Logger.get("Code-Cache-GC-Trigger");

                while (!Thread.currentThread().isInterrupted()) {
                    long used = codeCacheMbean.getUsage().getUsed();
                    long max = codeCacheMbean.getUsage().getMax();
                    if (used > 0.7 * max) {
                        // Due to some obscure bug in hotspot (java 7), once the code cache fills up the JIT stops compiling and never recovers from this condition.
                        // By forcing classes to unload from the perm gen, we let the code cache evictor make room before the cache fills up.
                        // For best results, the server should be run with -XX:+UseConcMarkSweepGC -XX:+ExplicitGCInvokesConcurrent -XX:+CMSClassUnloadingEnabled
                        log.info("Triggering GC to avoid Code Cache eviction bugs");
                        System.gc();
                    }
                    else if (used > 0.95 * max) {
                        log.error("Code Cache is more than 95% full. JIT may stop working.");
                    }

                    try {
                        Thread.sleep(1000);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
        gcThread.setDaemon(true);
        gcThread.setName("Code-Cache-GC-Trigger");
        gcThread.start();
    }

    private static MemoryPoolMXBean findCodeCacheMBean()
    {
        for (MemoryPoolMXBean bean : ManagementFactory.getMemoryPoolMXBeans()) {
            if (bean.getName().equals("Code Cache")) {
                return bean;
            }
        }
        return null;
    }

    @Override
    public void run()
    {
        Logger log = Logger.get(PrestoServer.class);

        ImmutableList.Builder<Module> modules = ImmutableList.builder();
        modules.add(
                new NodeModule(),
                new DiscoveryModule(),
                new HttpServerModule(),
                new JsonModule(),
                new JaxrsModule(),
                new MBeanModule(),
                new JmxModule(),
                new JmxHttpModule(),
                new LogJmxModule(),
                new TraceTokenModule(),
                new JsonEventModule(),
                new HttpEventModule(),
                new ServerMainModule());

        modules.addAll(getAdditionalModules());

        Bootstrap app = new Bootstrap(modules.build());

        try {
            Injector injector = app.strictConfig().initialize();

            if (!FloatingDecimal.isPatchInstalled()) {
                log.warn("FloatingDecimal patch not installed. Parallelism will be diminished when parsing/formatting doubles");
            }

            injector.getInstance(PluginManager.class).loadPlugins();

            injector.getInstance(CatalogManager.class).loadCatalogs();

            injector.getInstance(Announcer.class).start();

            log.info("======== SERVER STARTED ========");

            installCodeCacheGcTrigger();
        }
        catch (Throwable e) {
            log.error(e);
            System.exit(1);
        }
    }

    protected Iterable<? extends Module> getAdditionalModules()
    {
        return ImmutableList.of();
    }
}
