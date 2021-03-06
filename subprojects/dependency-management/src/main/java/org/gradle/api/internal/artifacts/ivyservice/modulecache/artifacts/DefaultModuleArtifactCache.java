/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.ivyservice.modulecache.artifacts;

import com.google.common.collect.Maps;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.CacheLockingManager;
import org.gradle.api.internal.artifacts.metadata.ComponentArtifactIdentifierSerializer;
import org.gradle.api.internal.artifacts.metadata.ModuleComponentFileArtifactIdentifierSerializer;
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier;
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier;
import org.gradle.internal.resource.cached.AbstractCachedIndex;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.DefaultSerializerRegistry;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;
import org.gradle.util.BuildCommencedTimeProvider;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DefaultModuleArtifactCache extends AbstractCachedIndex<ArtifactAtRepositoryKey, CachedArtifact> implements ModuleArtifactCache {
    private static final ArtifactAtRepositoryKeySerializer KEY_SERIALIZER = keySerializer();
    private static final CachedArtifactSerializer VALUE_SERIALIZER = new CachedArtifactSerializer();
    private final BuildCommencedTimeProvider timeProvider;
    private final Map<ArtifactAtRepositoryKey, CachedArtifact> inMemoryCache = Maps.newConcurrentMap();

    public DefaultModuleArtifactCache(String persistentCacheFile, BuildCommencedTimeProvider timeProvider, CacheLockingManager cacheLockingManager) {
        super(persistentCacheFile, KEY_SERIALIZER, VALUE_SERIALIZER, cacheLockingManager);
        this.timeProvider = timeProvider;
    }

    protected static ArtifactAtRepositoryKeySerializer keySerializer() {
        DefaultSerializerRegistry serializerRegistry = new DefaultSerializerRegistry();
        serializerRegistry.register(DefaultModuleComponentArtifactIdentifier.class, new ComponentArtifactIdentifierSerializer());
        serializerRegistry.register(ModuleComponentFileArtifactIdentifier.class, new ModuleComponentFileArtifactIdentifierSerializer());
        return new ArtifactAtRepositoryKeySerializer(serializerRegistry.build(ComponentArtifactIdentifier.class));
    }

    public void store(final ArtifactAtRepositoryKey key, final File artifactFile, BigInteger moduleDescriptorHash,
        long cachedFileLastModified) {
        assertArtifactFileNotNull(artifactFile);
        assertKeyNotNull(key);
        storeInternal(key, createEntry(artifactFile, moduleDescriptorHash, cachedFileLastModified));
    }

    private DefaultCachedArtifact createEntry(File artifactFile, BigInteger moduleDescriptorHash,
        long cachedFileLastModified) {
        return new DefaultCachedArtifact(artifactFile, timeProvider.getCurrentTime(), moduleDescriptorHash,
            cachedFileLastModified);
    }

    public void storeMissing(ArtifactAtRepositoryKey key, List<String> attemptedLocations, BigInteger descriptorHash) {
        storeInternal(key, createMissingEntry(attemptedLocations, descriptorHash));
    }

    private CachedArtifact createMissingEntry(List<String> attemptedLocations, BigInteger descriptorHash) {
        return new DefaultCachedArtifact(attemptedLocations, timeProvider.getCurrentTime(), descriptorHash);
    }

    @Override
    protected void storeInternal(ArtifactAtRepositoryKey key, CachedArtifact entry) {
        inMemoryCache.put(key, entry);
        super.storeInternal(key, entry);
    }

    @Override
    public CachedArtifact lookup(ArtifactAtRepositoryKey key) {
        CachedArtifact inMemoryCachedArtifact = inMemoryCache.get(key);
        if (inMemoryCachedArtifact != null) {
            return inMemoryCachedArtifact;
        }

        CachedArtifact cachedArtifact = super.lookup(key);
        if (cachedArtifact != null) {
            inMemoryCache.put(key, cachedArtifact);
            return cachedArtifact;
        }

        return null;
    }

    @Override
    public void clear(ArtifactAtRepositoryKey key) {
        super.clear(key);
        inMemoryCache.remove(key);
    }

    private static class ArtifactAtRepositoryKeySerializer implements Serializer<ArtifactAtRepositoryKey> {
        private final Serializer<ComponentArtifactIdentifier> artifactIdSerializer;

        public ArtifactAtRepositoryKeySerializer(Serializer<ComponentArtifactIdentifier> artifactIdSerializer) {
            this.artifactIdSerializer = artifactIdSerializer;
        }

        public void write(Encoder encoder, ArtifactAtRepositoryKey value) throws Exception {
            encoder.writeString(value.getRepositoryId());
            artifactIdSerializer.write(encoder, value.getArtifactId());
        }

        public ArtifactAtRepositoryKey read(Decoder decoder) throws Exception {
            String repositoryId = decoder.readString();
            ComponentArtifactIdentifier artifactIdentifier = artifactIdSerializer.read(decoder);
            return new ArtifactAtRepositoryKey(repositoryId, artifactIdentifier);
        }
    }

    private static class CachedArtifactSerializer implements Serializer<CachedArtifact> {
        public void write(Encoder encoder, CachedArtifact value) throws Exception {
            encoder.writeBoolean(value.isMissing());
            encoder.writeLong(value.getCachedAt());
            byte[] hash = value.getDescriptorHash().toByteArray();
            encoder.writeBinary(hash);
            if (!value.isMissing()) {
                encoder.writeString(value.getCachedFile().getPath());
                encoder.writeLong(value.getCachedFileLastModified());
            } else {
                encoder.writeSmallInt(value.attemptedLocations().size());
                for (String location : value.attemptedLocations()) {
                    encoder.writeString(location);
                }
            }
        }

        public CachedArtifact read(Decoder decoder) throws Exception {
            boolean isMissing = decoder.readBoolean();
            long createTimestamp = decoder.readLong();
            byte[] encodedHash = decoder.readBinary();
            BigInteger hash = new BigInteger(encodedHash);
            if (!isMissing) {
                File file = new File(decoder.readString());
                long cachedFileLastModified = decoder.readLong();
                return new DefaultCachedArtifact(file, createTimestamp, hash, cachedFileLastModified);
            } else {
                int size = decoder.readSmallInt();
                List<String> attempted = new ArrayList<String>(size);
                for (int i = 0; i < size; i++) {
                    attempted.add(decoder.readString());
                }
                return new DefaultCachedArtifact(attempted, createTimestamp, hash);
            }
        }
    }

}
