/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.index;

import static org.apache.phoenix.hbase.index.write.IndexWriterUtils.DEFAULT_INDEX_WRITER_RPC_PAUSE;
import static org.apache.phoenix.hbase.index.write.IndexWriterUtils.DEFAULT_INDEX_WRITER_RPC_RETRIES_NUMBER;
import static org.apache.phoenix.hbase.index.write.IndexWriterUtils.INDEX_WRITER_RPC_PAUSE;
import static org.apache.phoenix.hbase.index.write.IndexWriterUtils.INDEX_WRITER_RPC_RETRIES_NUMBER;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.ipc.RpcControllerFactory;
import org.apache.hadoop.hbase.ipc.controller.InterRegionServerIndexRpcControllerFactory;
import org.apache.hadoop.hbase.regionserver.MiniBatchOperationInProgress;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.coprocessor.DelegateRegionCoprocessorEnvironment;
import org.apache.phoenix.execute.PhoenixTxIndexMutationGenerator;
import org.apache.phoenix.hbase.index.write.IndexWriter;
import org.apache.phoenix.hbase.index.write.LeaveIndexActiveFailurePolicy;
import org.apache.phoenix.hbase.index.write.ParallelWriterIndexCommitter;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.trace.TracingUtils;
import org.apache.phoenix.trace.util.NullSpan;
import org.apache.phoenix.transaction.PhoenixTransactionContext;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.ServerUtil;
import org.cloudera.htrace.Span;
import org.cloudera.htrace.Trace;
import org.cloudera.htrace.TraceScope;

/**
 * Do all the work of managing local index updates for a transactional table from a single coprocessor. Since the transaction
 * manager essentially time orders writes through conflict detection, the logic to maintain a secondary index is quite a
 * bit simpler than the non transactional case. For example, there's no need to muck with the WAL, as failure scenarios
 * are handled by aborting the transaction.
 */
public class PhoenixTransactionalIndexer extends BaseRegionObserver {

    private static final Log LOG = LogFactory.getLog(PhoenixTransactionalIndexer.class);

    // Hack to get around not being able to save any state between
    // coprocessor calls. TODO: remove after HBASE-18127 when available
    private static class BatchMutateContext {
        public Collection<Pair<Mutation, byte[]>> indexUpdates = Collections.emptyList();
        public final int clientVersion;

        public BatchMutateContext(int clientVersion) {
            this.clientVersion = clientVersion;
        }
    }
    
    private ThreadLocal<BatchMutateContext> batchMutateContext =
            new ThreadLocal<BatchMutateContext>();
    
    private PhoenixIndexCodec codec;
    private IndexWriter writer;
    private boolean stopped;

    @Override
    public void start(CoprocessorEnvironment e) throws IOException {
        final RegionCoprocessorEnvironment env = (RegionCoprocessorEnvironment)e;
        Configuration conf = e.getConfiguration();
        String serverName = env.getRegionServerServices().getServerName().getServerName();
        codec = new PhoenixIndexCodec(conf, env.getRegion().getRegionInfo().getStartKey(), env.getRegion().getRegionInfo().getEndKey(), env.getRegionInfo().getTable().getName());
        // Clone the config since it is shared
        Configuration clonedConfig = PropertiesUtil.cloneConfig(e.getConfiguration());
        /*
         * Set the rpc controller factory so that the HTables used by IndexWriter would
         * set the correct priorities on the remote RPC calls.
         */
        clonedConfig.setClass(RpcControllerFactory.CUSTOM_CONTROLLER_CONF_KEY,
                InterRegionServerIndexRpcControllerFactory.class, RpcControllerFactory.class);
        // lower the number of rpc retries.  We inherit config from HConnectionManager#setServerSideHConnectionRetries,
        // which by default uses a multiplier of 10.  That is too many retries for our synchronous index writes
        clonedConfig.setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER,
            env.getConfiguration().getInt(INDEX_WRITER_RPC_RETRIES_NUMBER,
                DEFAULT_INDEX_WRITER_RPC_RETRIES_NUMBER));
        clonedConfig.setInt(HConstants.HBASE_CLIENT_PAUSE, env.getConfiguration()
            .getInt(INDEX_WRITER_RPC_PAUSE, DEFAULT_INDEX_WRITER_RPC_PAUSE));
        DelegateRegionCoprocessorEnvironment indexWriterEnv = new DelegateRegionCoprocessorEnvironment(clonedConfig, env);
        // setup the actual index writer
        // For transactional tables, we keep the index active upon a write failure
        // since we have the all versus none behavior for transactions. Also, we
        // fail on any write exception since this will end up failing the transaction.
        this.writer = new IndexWriter(IndexWriter.getCommitter(indexWriterEnv, ParallelWriterIndexCommitter.class),
                new LeaveIndexActiveFailurePolicy(), indexWriterEnv, serverName + "-tx-index-writer");
    }

    @Override
    public void stop(CoprocessorEnvironment e) throws IOException {
        if (this.stopped) { return; }
        this.stopped = true;
        String msg = "TxIndexer is being stopped";
        this.writer.stop(msg);
    }

    private static Iterator<Mutation> getMutationIterator(final MiniBatchOperationInProgress<Mutation> miniBatchOp) {
        return new Iterator<Mutation>() {
            private int i = 0;
            
            @Override
            public boolean hasNext() {
                return i < miniBatchOp.size();
            }

            @Override
            public Mutation next() {
                return miniBatchOp.getOperation(i++);
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
            
        };
    }
    
    @Override
    public void preBatchMutate(ObserverContext<RegionCoprocessorEnvironment> c,
            MiniBatchOperationInProgress<Mutation> miniBatchOp) throws IOException {

        Mutation m = miniBatchOp.getOperation(0);
        if (!codec.isEnabled(m)) {
            super.preBatchMutate(c, miniBatchOp);
            return;
        }

        PhoenixIndexMetaData indexMetaData = new PhoenixIndexMetaDataBuilder(c.getEnvironment()).getIndexMetaData(miniBatchOp);
        if (    indexMetaData.getClientVersion() >= PhoenixDatabaseMetaData.MIN_TX_CLIENT_SIDE_MAINTENANCE
            && !indexMetaData.hasLocalIndexes()) { // Still generate index updates server side for local indexes
            super.preBatchMutate(c, miniBatchOp);
            return;
        }
        BatchMutateContext context = new BatchMutateContext(indexMetaData.getClientVersion());
        setBatchMutateContext(c, context);
        
        Collection<Pair<Mutation, byte[]>> indexUpdates = null;
        // get the current span, or just use a null-span to avoid a bunch of if statements
        try (TraceScope scope = Trace.startSpan("Starting to build index updates")) {
            Span current = scope.getSpan();
            if (current == null) {
                current = NullSpan.INSTANCE;
            }

            RegionCoprocessorEnvironment env = c.getEnvironment();
            PhoenixTransactionContext txnContext = indexMetaData.getTransactionContext();
            if (txnContext == null) {
                throw new NullPointerException("Expected to find transaction in metadata for " + env.getRegionInfo().getTable().getNameAsString());
            }
            PhoenixTxIndexMutationGenerator generator = new PhoenixTxIndexMutationGenerator(env.getConfiguration(), indexMetaData,
                    env.getRegionInfo().getTable().getName(), 
                    env.getRegionInfo().getStartKey(), 
                    env.getRegionInfo().getEndKey());
            try (HTableInterface htable = env.getTable(env.getRegionInfo().getTable())) {
                // get the index updates for all elements in this batch
                context.indexUpdates = generator.getIndexUpdates(htable, getMutationIterator(miniBatchOp));
            }
            current.addTimelineAnnotation("Built index updates, doing preStep");
            TracingUtils.addAnnotation(current, "index update count", context.indexUpdates.size());
        } catch (Throwable t) {
            String msg = "Failed to update index with entries:" + indexUpdates;
            LOG.error(msg, t);
            ServerUtil.throwIOException(msg, t);
        }
    }

    @Override
    public void postBatchMutateIndispensably(ObserverContext<RegionCoprocessorEnvironment> c,
        MiniBatchOperationInProgress<Mutation> miniBatchOp, final boolean success) throws IOException {
        BatchMutateContext context = getBatchMutateContext(c);
        if (context == null || context.indexUpdates == null) {
            return;
        }
        // get the current span, or just use a null-span to avoid a bunch of if statements
        try (TraceScope scope = Trace.startSpan("Starting to write index updates")) {
            Span current = scope.getSpan();
            if (current == null) {
                current = NullSpan.INSTANCE;
            }

            if (success) { // if miniBatchOp was successfully written, write index updates
                if (!context.indexUpdates.isEmpty()) {
                    this.writer.write(context.indexUpdates, true, context.clientVersion);
                }
                current.addTimelineAnnotation("Wrote index updates");
            }
        } catch (Throwable t) {
            String msg = "Failed to write index updates:" + context.indexUpdates;
            LOG.error(msg, t);
            ServerUtil.throwIOException(msg, t);
         } finally {
             removeBatchMutateContext(c);
         }
    }

    private void setBatchMutateContext(ObserverContext<RegionCoprocessorEnvironment> c, BatchMutateContext context) {
        this.batchMutateContext.set(context);
    }
    
    private BatchMutateContext getBatchMutateContext(ObserverContext<RegionCoprocessorEnvironment> c) {
        return this.batchMutateContext.get();
    }
    
    private void removeBatchMutateContext(ObserverContext<RegionCoprocessorEnvironment> c) {
        this.batchMutateContext.remove();
    }
}
