/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package querqy.opensearch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchException;
import org.opensearch.ResourceNotFoundException;
import org.opensearch.action.get.GetResponse;
import org.opensearch.client.Client;
import org.opensearch.common.cache.Cache;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.index.IndexService;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.indices.InvalidTypeNameException;
import querqy.opensearch.rewriterstore.LoadRewriterConfig;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;
import querqy.rewrite.RewriteChain;
import querqy.rewrite.RewriterFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

public class RewriterShardContext {


    public static final Setting<TimeValue> CACHE_EXPIRE_AFTER_WRITE = Setting.timeSetting(
            "querqy.caches.rewriter.expire_after_write",
            TimeValue.timeValueNanos(0), // do not expire by default
            TimeValue.timeValueNanos(0),
            Setting.Property.NodeScope);

    public static final Setting<TimeValue> CACHE_EXPIRE_AFTER_READ = Setting.timeSetting(
            "querqy.caches.rewriter.expire_after_read",
            TimeValue.timeValueNanos(0), // do not expire by default
            TimeValue.timeValueNanos(0),
            Setting.Property.NodeScope);

    private static final Logger LOGGER = LogManager.getLogger(RewriterShardContext.class);

    final Cache<String, RewriterFactoryAndLogging> factories;
    final Client client;
    final IndexService indexService;
    final ShardId shardId;

    public RewriterShardContext(final ShardId shardId, final IndexService indexService, final Settings settings,
                                final Client client) {
        this.indexService = indexService;
        this.shardId = shardId;
        this.client = client;
        factories = Caches.buildCache(CACHE_EXPIRE_AFTER_READ.get(settings), CACHE_EXPIRE_AFTER_WRITE.get(settings));
        LOGGER.info("Context loaded for shard {} {}", shardId, shardId.getIndex());
    }

    public RewriteChainAndLogging getRewriteChain(final List<String> rewriterIds) {
        final List<RewriterFactory> rewriterFactories = new ArrayList<>(rewriterIds.size());
        final Set<String> loggingEnabledRewriters = new HashSet<>();

        for (final String id : rewriterIds) {

            RewriterFactoryAndLogging factoryAndLogging = factories.get(id);
            if (factoryAndLogging == null) {
                factoryAndLogging = loadFactory(id, false);
            }
            rewriterFactories.add(factoryAndLogging.rewriterFactory);
            if (factoryAndLogging.loggingEnabled) {
                loggingEnabledRewriters.add(id);
            }

        }

        return new RewriteChainAndLogging(new RewriteChain(rewriterFactories), loggingEnabledRewriters);
    }

    public void clearRewriter(final String rewriterId) {
        factories.invalidate(rewriterId);
    }

    public void clearRewriters() {
        factories.invalidateAll();
    }

    public void reloadRewriter(final String rewriterId) {
        if (factories.get(rewriterId) != null) {
            loadFactory(rewriterId, true);
        }
    }

    public synchronized RewriterFactoryAndLogging loadFactory(final String rewriterId, final boolean forceLoad) {

        RewriterFactoryAndLogging factoryAndLogging = factories.get(rewriterId);

        if (forceLoad || (factoryAndLogging == null)) {

            final GetResponse response;

            try {
                response = client.prepareGet(QUERQY_INDEX_NAME, rewriterId).execute().get();
            } catch (InterruptedException | ExecutionException e) {
                throw new OpenSearchException("Could not load rewriter " + rewriterId, e);
            }

            final Map<String, Object> source = response.getSource();

            if (source == null) {
                throw new ResourceNotFoundException("Rewriter not found: " + rewriterId);
            }

            if (!"rewriter".equals(source.get(RewriterConfigMapping.PROP_TYPE))) {
                throw new InvalidTypeNameException("Not a rewriter: " + rewriterId);
            }

            final LoadRewriterConfig loadConfig = new LoadRewriterConfig(rewriterId, source);

            final Map<String, Object> infoLogging = loadConfig.getInfoLoggingConfig();
            final boolean loggingEnabled;
            if (infoLogging != null) {
                final Object sinksObj = infoLogging.get("sinks");
                if (sinksObj instanceof String) {
                    loggingEnabled = "log4j".equals(sinksObj);
                } else if (sinksObj instanceof Collection<?>) {
                    Collection<?> sinksCollection = (Collection<?>) sinksObj;
                    loggingEnabled = (sinksCollection.size() > 0) && sinksCollection.contains("log4j");
                } else {
                    loggingEnabled = false;
                }
            } else {
                loggingEnabled = false;
            }

            final RewriterFactory factory = OpenSearchRewriterFactory.loadConfiguredInstance(loadConfig)
                    .createRewriterFactory(indexService.getShard(shardId.id()));
            factoryAndLogging = new RewriterFactoryAndLogging(factory, loggingEnabled);
            factories.put(rewriterId, factoryAndLogging);


        }

        return factoryAndLogging;

    }


    public static class RewriterFactoryAndLogging {
        public final RewriterFactory rewriterFactory;
        public final boolean loggingEnabled;

        public RewriterFactoryAndLogging(final RewriterFactory rewriterFactory, final boolean loggingEnabled) {
            this.rewriterFactory = rewriterFactory;
            this.loggingEnabled = loggingEnabled;
        }
    }
}
