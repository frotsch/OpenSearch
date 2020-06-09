/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.ilm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexAbstraction;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.index.Index;

import java.util.Locale;
import java.util.Objects;

/**
 * This step replaces a data stream backing index with the target index, as part of the data stream's backing indices.
 * Eg. if data stream `foo-stream` is backed by indices [`foo-stream-000001`, `foo-stream-000002`] and we'd like to replace the first
 * generation index, `foo-stream-000001`, with `shrink-foo-stream-000001`, after this step the `foo-stream` data stream will contain
 * the following indices
 * <p>
 * [`shrink-foo-stream-000001`, `foo-stream-000002`]
 * <p>
 * The `foo-stream-000001` index will continue to exist but will not be part of the data stream anymore.
 * <p>
 * As the last generation is the write index of the data stream, replacing the last generation index is not allowed.
 * <p>
 * This is useful in scenarios following a restore from snapshot operation where the restored index will take the place of the source
 * index in the ILM lifecycle or in the case where we shrink an index and the shrunk index will take the place of the original index.
 */
public class ReplaceDataStreamBackingIndexStep extends ClusterStateActionStep {
    public static final String NAME = "replace-datastream-backing-index";
    private static final Logger logger = LogManager.getLogger(ReplaceDataStreamBackingIndexStep.class);

    private final String targetIndexPrefix;

    public ReplaceDataStreamBackingIndexStep(StepKey key, StepKey nextStepKey, String targetIndexPrefix) {
        super(key, nextStepKey);
        this.targetIndexPrefix = targetIndexPrefix;
    }

    @Override
    public boolean isRetryable() {
        return true;
    }

    public String getTargetIndexPrefix() {
        return targetIndexPrefix;
    }

    @Override
    public ClusterState performAction(Index index, ClusterState clusterState) {
        String originalIndex = index.getName();
        final String targetIndexName = targetIndexPrefix + originalIndex;

        IndexMetadata originalIndexMetadata = clusterState.metadata().index(index);
        if (originalIndexMetadata == null) {
            // Index must have been since deleted, skip the shrink action
            logger.debug("[{}] lifecycle action for index [{}] executed but index no longer exists", NAME, index.getName());
            return clusterState;
        }

        String policyName = originalIndexMetadata.getSettings().get(LifecycleSettings.LIFECYCLE_NAME);
        IndexAbstraction indexAbstraction = clusterState.metadata().getIndicesLookup().get(index.getName());
        assert indexAbstraction != null : "invalid cluster metadata. index [" + index.getName() + "] was not found";
        IndexAbstraction.DataStream dataStream = indexAbstraction.getParentDataStream();
        if (dataStream == null) {
            String errorMessage = String.format(Locale.ROOT, "index [%s] is not part of a data stream. stopping execution of lifecycle " +
                "[%s] until the index is added to a data stream", originalIndex, policyName);
            logger.debug(errorMessage);
            throw new IllegalStateException(errorMessage);
        }

        assert dataStream.getWriteIndex() != null : dataStream.getName() + " has no write index";
        if (dataStream.getWriteIndex().getIndex().getName().equals(originalIndex)) {
            String errorMessage = String.format(Locale.ROOT, "index [%s] is the write index for data stream [%s]. stopping execution of " +
                    "lifecycle [%s] as a data stream's write index cannot be replaced. manually rolling over the index will resume the " +
                    "execution of the policy as the index will not be the data stream's write index anymore", originalIndex,
                dataStream.getName(), policyName);
            logger.debug(errorMessage);
            throw new IllegalStateException(errorMessage);
        }

        IndexMetadata targetIndexMetadata = clusterState.metadata().index(targetIndexName);
        if (targetIndexMetadata == null) {
            String errorMessage = String.format(Locale.ROOT, "target index [%s] doesn't exist. stopping execution of lifecycle [%s] for" +
                " index [%s]", targetIndexName, policyName, originalIndex);
            logger.debug(errorMessage);
            throw new IllegalStateException(errorMessage);
        }

        Metadata.Builder newMetaData = Metadata.builder(clusterState.getMetadata())
            .put(dataStream.getDataStream().replaceBackingIndex(index, targetIndexMetadata.getIndex()));
        return ClusterState.builder(clusterState).metadata(newMetaData).build();
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), targetIndexPrefix);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        ReplaceDataStreamBackingIndexStep other = (ReplaceDataStreamBackingIndexStep) obj;
        return super.equals(obj) &&
            Objects.equals(targetIndexPrefix, other.targetIndexPrefix);
    }
}
