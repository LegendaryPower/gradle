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

package org.gradle.api.internal.artifacts.ivyservice.modulecache;

import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import org.gradle.internal.component.external.model.AbstractRealisedModuleComponentResolveMetadata;
import org.gradle.internal.component.external.model.DefaultIvyModuleResolveMetadata;
import org.gradle.internal.component.external.model.DefaultMavenModuleResolveMetadata;
import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata;
import org.gradle.internal.component.external.model.RealisedIvyModuleResolveMetadata;
import org.gradle.internal.component.external.model.RealisedMavenModuleResolveMetadata;
import org.gradle.internal.serialize.AbstractSerializer;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;

import java.io.EOFException;

public class ModuleComponentResolveMetadataSerializer extends AbstractSerializer<ModuleComponentResolveMetadata> {

    private final ModuleMetadataSerializer delegate;
    private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;

    public ModuleComponentResolveMetadataSerializer(ModuleMetadataSerializer delegate, ImmutableModuleIdentifierFactory moduleIdentifierFactory) {
        this.delegate = delegate;
        this.moduleIdentifierFactory = moduleIdentifierFactory;
    }

    @Override
    public ModuleComponentResolveMetadata read(Decoder decoder) throws EOFException, Exception {

        return delegate.read(decoder, moduleIdentifierFactory).asImmutable();
    }

    @Override
    public void write(Encoder encoder, ModuleComponentResolveMetadata value) throws Exception {
        AbstractRealisedModuleComponentResolveMetadata transformed = transformToRealisedForSerialization(value);
        delegate.write(encoder, transformed);
        // TODO LJA Serialize extra state
    }

    private AbstractRealisedModuleComponentResolveMetadata transformToRealisedForSerialization(ModuleComponentResolveMetadata metadata) {
        // TODO LJA REMOVE
        if (metadata instanceof AbstractRealisedModuleComponentResolveMetadata) {
            return (AbstractRealisedModuleComponentResolveMetadata) metadata;
        } else if (metadata instanceof DefaultIvyModuleResolveMetadata) {
            return RealisedIvyModuleResolveMetadata.transform((DefaultIvyModuleResolveMetadata) metadata);
        } else if (metadata instanceof DefaultMavenModuleResolveMetadata) {
            return RealisedMavenModuleResolveMetadata.transform((DefaultMavenModuleResolveMetadata) metadata);
        }
        throw new IllegalStateException("The type of metadata received is not supported - " + metadata.getClass().getName());

    }
}
