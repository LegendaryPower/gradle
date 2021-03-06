/*
 * Copyright 2016 the original author or authors.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier;
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import org.gradle.api.internal.artifacts.ModuleComponentSelectorSerializer;
import org.gradle.api.internal.artifacts.ivyservice.NamespaceId;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DefaultExcludeRuleConverter;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ExcludeRuleConverter;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.AttributeContainerSerializer;
import org.gradle.api.internal.artifacts.repositories.metadata.IvyMutableModuleMetadataFactory;
import org.gradle.api.internal.artifacts.repositories.metadata.MavenMutableModuleMetadataFactory;
import org.gradle.api.internal.artifacts.repositories.resolver.MavenUniqueSnapshotComponentIdentifier;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.internal.component.external.descriptor.Artifact;
import org.gradle.internal.component.external.descriptor.Configuration;
import org.gradle.internal.component.external.descriptor.DefaultExclude;
import org.gradle.internal.component.external.descriptor.MavenScope;
import org.gradle.internal.component.external.model.ComponentVariant;
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier;
import org.gradle.internal.component.external.model.IvyDependencyDescriptor;
import org.gradle.internal.component.external.model.IvyModuleResolveMetadata;
import org.gradle.internal.component.external.model.MavenDependencyDescriptor;
import org.gradle.internal.component.external.model.MavenModuleResolveMetadata;
import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata;
import org.gradle.internal.component.external.model.MutableComponentVariant;
import org.gradle.internal.component.external.model.MutableIvyModuleResolveMetadata;
import org.gradle.internal.component.external.model.MutableMavenModuleResolveMetadata;
import org.gradle.internal.component.external.model.MutableModuleComponentResolveMetadata;
import org.gradle.internal.component.model.DefaultIvyArtifactName;
import org.gradle.internal.component.model.Exclude;
import org.gradle.internal.component.model.ExcludeMetadata;
import org.gradle.internal.component.model.IvyArtifactName;
import org.gradle.internal.hash.HashValue;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ModuleMetadataSerializer {
    private static final byte TYPE_IVY = 1;
    private static final byte TYPE_MAVEN = 2;

    private final ModuleComponentSelectorSerializer componentSelectorSerializer;
    private final MavenMutableModuleMetadataFactory mavenMetadataFactory;
    private final IvyMutableModuleMetadataFactory ivyMetadataFactory;
    private final AttributeContainerSerializer attributeContainerSerializer;

    public ModuleMetadataSerializer(AttributeContainerSerializer attributeContainerSerializer, MavenMutableModuleMetadataFactory mavenMetadataFactory, IvyMutableModuleMetadataFactory ivyMetadataFactory) {
        this.mavenMetadataFactory = mavenMetadataFactory;
        this.ivyMetadataFactory = ivyMetadataFactory;
        this.attributeContainerSerializer = attributeContainerSerializer;
        this.componentSelectorSerializer = new ModuleComponentSelectorSerializer(attributeContainerSerializer);
    }

    public MutableModuleComponentResolveMetadata read(Decoder decoder, ImmutableModuleIdentifierFactory moduleIdentifierFactory) throws IOException {
        return new Reader(decoder, moduleIdentifierFactory, attributeContainerSerializer, componentSelectorSerializer, mavenMetadataFactory, ivyMetadataFactory).read();
    }

    public void write(Encoder encoder, ModuleComponentResolveMetadata metadata) throws IOException {
        new Writer(encoder, attributeContainerSerializer, componentSelectorSerializer).write(metadata);
    }

    private static class Writer {
        private final Encoder encoder;
        private final AttributeContainerSerializer attributeContainerSerializer;
        private final ModuleComponentSelectorSerializer componentSelectorSerializer;

        private Writer(Encoder encoder, AttributeContainerSerializer attributeContainerSerializer, ModuleComponentSelectorSerializer componentSelectorSerializer) {
            this.encoder = encoder;
            this.attributeContainerSerializer = attributeContainerSerializer;
            this.componentSelectorSerializer = componentSelectorSerializer;
        }

        public void write(ModuleComponentResolveMetadata metadata) throws IOException {
            if (metadata instanceof IvyModuleResolveMetadata) {
                write((IvyModuleResolveMetadata) metadata);
            } else if (metadata instanceof MavenModuleResolveMetadata) {
                write((MavenModuleResolveMetadata) metadata);
            } else {
                throw new IllegalArgumentException("Unexpected metadata type: " + metadata.getClass());
            }
        }

        private void write(MavenModuleResolveMetadata metadata) throws IOException {
            encoder.writeByte(TYPE_MAVEN);
            writeInfoSection(metadata);
            writeNullableString(metadata.getSnapshotTimestamp());
            writeMavenDependencies(metadata.getDependencies());
            writeSharedInfo(metadata);
            writeNullableString(metadata.getPackaging());
            writeBoolean(metadata.isRelocated());
            writeVariants(metadata);
        }

        private void writeVariants(ModuleComponentResolveMetadata metadata) throws IOException {
            encoder.writeSmallInt(metadata.getVariants().size());
            for (ComponentVariant variant : metadata.getVariants()) {
                encoder.writeString(variant.getName());
                writeAttributes(variant.getAttributes());
                writeVariantDependencies(variant.getDependencies());
                writeVariantConstraints(variant.getDependencyConstraints());
                writeVariantFiles(variant.getFiles());
                writeVariantCapabilities(variant.getCapabilities().getCapabilities());
            }
        }

        private void writeVariantConstraints(ImmutableList<? extends ComponentVariant.DependencyConstraint> constraints) throws IOException {
            encoder.writeSmallInt(constraints.size());
            for (ComponentVariant.DependencyConstraint constraint : constraints) {
                componentSelectorSerializer.write(encoder, constraint.getGroup(), constraint.getModule(), constraint.getVersionConstraint(), constraint.getAttributes());
                encoder.writeNullableString(constraint.getReason());
            }
        }

        private void writeVariantDependencies(List<? extends ComponentVariant.Dependency> dependencies) throws IOException {
            encoder.writeSmallInt(dependencies.size());
            for (ComponentVariant.Dependency dependency : dependencies) {
                componentSelectorSerializer.write(encoder, dependency.getGroup(), dependency.getModule(), dependency.getVersionConstraint(), dependency.getAttributes());
                encoder.writeNullableString(dependency.getReason());
                writeVariantDependencyExcludes(dependency.getExcludes());
            }
        }

        private void writeVariantDependencyExcludes(List<ExcludeMetadata> excludes) throws IOException {
            writeCount(excludes.size());
            for (ExcludeMetadata exclude : excludes) {
                writeString(exclude.getModuleId().getGroup());
                writeString(exclude.getModuleId().getName());
            }
        }

        private void writeAttributes(AttributeContainer attributes) throws IOException {
            attributeContainerSerializer.write(encoder, attributes);
        }

        private void writeVariantFiles(List<? extends ComponentVariant.File> files) throws IOException {
            encoder.writeSmallInt(files.size());
            for (ComponentVariant.File file : files) {
                encoder.writeString(file.getName());
                encoder.writeString(file.getUri());
            }
        }

        private void writeVariantCapabilities(List<? extends Capability> capabilities) throws IOException {
            encoder.writeSmallInt(capabilities.size());
            for (Capability capability : capabilities) {
                encoder.writeString(capability.getGroup());
                encoder.writeString(capability.getName());
                encoder.writeString(capability.getVersion());
            }
        }


        private void write(IvyModuleResolveMetadata metadata) throws IOException {
            encoder.writeByte(TYPE_IVY);
            writeInfoSection(metadata);
            writeExtraInfo(metadata.getExtraAttributes());
            writeConfigurations(metadata.getConfigurationDefinitions().values());
            writeIvyDependencies(metadata.getDependencies());
            writeArtifacts(metadata.getArtifactDefinitions());
            writeExcludeRules(metadata.getExcludes());
            writeSharedInfo(metadata);
            writeNullableString(metadata.getBranch());
            writeVariants(metadata);
        }

        private void writeSharedInfo(ModuleComponentResolveMetadata metadata) throws IOException {
            encoder.writeBinary(metadata.getContentHash().asByteArray());
            encoder.writeBoolean(metadata.isMissing());
            encoder.writeString(metadata.getStatus());
        }

        private void writeId(ModuleComponentIdentifier componentIdentifier) throws IOException {
            writeString(componentIdentifier.getGroup());
            writeString(componentIdentifier.getModule());
            writeString(componentIdentifier.getVersion());
        }

        private void writeInfoSection(ModuleComponentResolveMetadata metadata) throws IOException {
            writeId(metadata.getId());
            writeAttributes(metadata.getAttributes());
        }

        private void writeExtraInfo(Map<NamespaceId, String> extraInfo) throws IOException {
            writeCount(extraInfo.size());
            for (Map.Entry<NamespaceId, String> entry : extraInfo.entrySet()) {
                NamespaceId namespaceId = entry.getKey();
                writeString(namespaceId.getNamespace());
                writeString(namespaceId.getName());
                writeString(entry.getValue());
            }
        }

        private void writeConfigurations(Collection<Configuration> configurations) throws IOException {
            writeCount(configurations.size());
            for (Configuration conf : configurations) {
                writeConfiguration(conf);
            }
        }

        private void writeConfiguration(Configuration conf) throws IOException {
            writeString(conf.getName());
            writeBoolean(conf.isTransitive());
            writeBoolean(conf.isVisible());
            writeStringList(conf.getExtendsFrom());
        }

        private void writeArtifacts(List<Artifact> artifacts) throws IOException {
            writeCount(artifacts.size());
            for (Artifact artifact : artifacts) {
                IvyArtifactName artifactName = artifact.getArtifactName();
                writeString(artifactName.getName());
                writeString(artifactName.getType());
                writeNullableString(artifactName.getExtension());
                writeNullableString(artifactName.getClassifier());
                writeStringSet(artifact.getConfigurations());
            }
        }

        private void writeIvyDependencies(List<IvyDependencyDescriptor> dependencies) throws IOException {
            writeCount(dependencies.size());
            for (IvyDependencyDescriptor dd : dependencies) {
                writeIvyDependency(dd);
            }
        }

        private void writeMavenDependencies(List<MavenDependencyDescriptor> dependencies) throws IOException {
            writeCount(dependencies.size());
            for (MavenDependencyDescriptor dd : dependencies) {
                writeMavenDependency(dd);
            }
        }

        private void writeIvyDependency(IvyDependencyDescriptor ivyDependency) throws IOException {
            componentSelectorSerializer.write(encoder, ivyDependency.getSelector());
            writeDependencyConfigurationMapping(ivyDependency);
            writeArtifacts(ivyDependency.getDependencyArtifacts());
            writeExcludeRules(ivyDependency.getAllExcludes());
            writeString(ivyDependency.getDynamicConstraintVersion());
            writeBoolean(ivyDependency.isChanging());
            writeBoolean(ivyDependency.isTransitive());
            writeBoolean(ivyDependency.isOptional());
        }

        private void writeDependencyConfigurationMapping(IvyDependencyDescriptor dep) throws IOException {
            SetMultimap<String, String> confMappings = dep.getConfMappings();
            writeCount(confMappings.keySet().size());
            for (String conf : confMappings.keySet()) {
                writeString(conf);
                writeStringSet(confMappings.get(conf));
            }
        }

        private void writeExcludeRules(List<Exclude> excludes) throws IOException {
            writeCount(excludes.size());
            for (Exclude exclude : excludes) {
                writeString(exclude.getModuleId().getGroup());
                writeString(exclude.getModuleId().getName());
                IvyArtifactName artifact = exclude.getArtifact();
                writeNullableArtifact(artifact);
                writeStringArray(exclude.getConfigurations().toArray(new String[0]));
                writeNullableString(exclude.getMatcher());
            }
        }

        private void writeMavenDependency(MavenDependencyDescriptor mavenDependency) throws IOException {
            componentSelectorSerializer.write(encoder, mavenDependency.getSelector());
            writeNullableArtifact(mavenDependency.getDependencyArtifact());
            writeMavenExcludeRules(mavenDependency.getAllExcludes());
            encoder.writeSmallInt(mavenDependency.getScope().ordinal());
            encoder.writeBoolean(mavenDependency.isOptional());
        }

        private void writeNullableArtifact(IvyArtifactName artifact) throws IOException {
            if (artifact == null) {
                writeBoolean(false);
            } else {
                writeBoolean(true);
                writeString(artifact.getName());
                writeString(artifact.getType());
                writeNullableString(artifact.getExtension());
                writeNullableString(artifact.getClassifier());
            }
        }

        private void writeMavenExcludeRules(List<ExcludeMetadata> excludes) throws IOException {
            writeCount(excludes.size());
            for (ExcludeMetadata exclude : excludes) {
                writeString(exclude.getModuleId().getGroup());
                writeString(exclude.getModuleId().getName());
            }
        }

        private void writeCount(int i) throws IOException {
            encoder.writeSmallInt(i);
        }

        private void writeString(String str) throws IOException {
            encoder.writeString(str);
        }

        private void writeNullableString(String str) throws IOException {
            encoder.writeNullableString(str);
        }

        private void writeBoolean(boolean b) throws IOException {
            encoder.writeBoolean(b);
        }

        private void writeStringArray(String[] values) throws IOException {
            writeCount(values.length);
            for (String configuration : values) {
                writeNullableString(configuration);
            }
        }

        private void writeStringList(List<String> values) throws IOException {
            writeCount(values.size());
            for (String configuration : values) {
                writeString(configuration);
            }
        }

        private void writeStringSet(Set<String> values) throws IOException {
            writeCount(values.size());
            for (String configuration : values) {
                writeString(configuration);
            }
        }
    }

    private static class Reader {
        private final Decoder decoder;
        private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;
        private final ExcludeRuleConverter excludeRuleConverter;
        private final AttributeContainerSerializer attributeContainerSerializer;
        private final ModuleComponentSelectorSerializer componentSelectorSerializer;
        private final MavenMutableModuleMetadataFactory mavenMetadataFactory;
        private final IvyMutableModuleMetadataFactory ivyMetadataFactory;
        private ModuleComponentIdentifier id;
        private ImmutableAttributes attributes;

        private Reader(Decoder decoder,
                       ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                       AttributeContainerSerializer attributeContainerSerializer,
                       ModuleComponentSelectorSerializer componentSelectorSerializer, MavenMutableModuleMetadataFactory mavenMutableModuleMetadataFactory,
                       IvyMutableModuleMetadataFactory ivyMetadataFactory) {
            this.decoder = decoder;
            this.moduleIdentifierFactory = moduleIdentifierFactory;
            this.excludeRuleConverter = new DefaultExcludeRuleConverter(moduleIdentifierFactory);
            this.attributeContainerSerializer = attributeContainerSerializer;
            this.componentSelectorSerializer = componentSelectorSerializer;
            this.mavenMetadataFactory = mavenMutableModuleMetadataFactory;
            this.ivyMetadataFactory = ivyMetadataFactory;
        }

        public MutableModuleComponentResolveMetadata read() throws IOException {
            byte type = decoder.readByte();
            switch (type) {
                case TYPE_IVY:
                    return readIvy();
                case TYPE_MAVEN:
                    return readMaven();
                default:
                    throw new IllegalArgumentException("Unexpected metadata type found.");
            }
        }

        private void readSharedInfo(MutableModuleComponentResolveMetadata metadata) throws IOException {
            metadata.setContentHash(new HashValue(decoder.readBinary()));
            metadata.setMissing(decoder.readBoolean());
            metadata.setStatus(decoder.readString());
        }

        private MutableModuleComponentResolveMetadata readMaven() throws IOException {
            readInfoSection();
            String snapshotTimestamp = readNullableString();
            if (snapshotTimestamp != null) {
                id = new MavenUniqueSnapshotComponentIdentifier(id, snapshotTimestamp);
            }

            List<MavenDependencyDescriptor> dependencies = readMavenDependencies();
            MutableMavenModuleResolveMetadata metadata = mavenMetadataFactory.create(id, dependencies);
            readSharedInfo(metadata);
            metadata.setSnapshotTimestamp(snapshotTimestamp);
            metadata.setPackaging(readNullableString());
            metadata.setRelocated(readBoolean());
            metadata.setAttributes(attributes);
            readVariants(metadata);
            return metadata;
        }

        private void readVariants(MutableModuleComponentResolveMetadata metadata) throws IOException {
            int count = decoder.readSmallInt();
            for (int i = 0; i < count; i++) {
                String name = decoder.readString();
                ImmutableAttributes attributes = readAttributes();
                MutableComponentVariant variant = metadata.addVariant(name, attributes);
                readVariantDependencies(variant);
                readVariantConstraints(variant);
                readVariantFiles(variant);
                readVariantCapabilities(variant);
            }
        }

        private ImmutableAttributes readAttributes() throws IOException {
            return attributeContainerSerializer.read(decoder);
        }

        private void readVariantDependencies(MutableComponentVariant variant) throws IOException {
            int count = decoder.readSmallInt();
            for (int i = 0; i < count; i++) {
                ModuleComponentSelector selector = componentSelectorSerializer.read(decoder);
                String reason = decoder.readNullableString();
                ImmutableList<ExcludeMetadata> excludes = readVariantDependencyExcludes();
                variant.addDependency(selector.getGroup(), selector.getModule(), selector.getVersionConstraint(), excludes, reason, (ImmutableAttributes) selector.getAttributes());
            }
        }

        private void readVariantConstraints(MutableComponentVariant variant) throws IOException {
            int count = decoder.readSmallInt();
            for (int i = 0; i < count; i++) {
                ModuleComponentSelector selector = componentSelectorSerializer.read(decoder);
                String reason = decoder.readNullableString();
                variant.addDependencyConstraint(selector.getGroup(), selector.getModule(), selector.getVersionConstraint(), reason, (ImmutableAttributes) selector.getAttributes());
            }
        }

        private ImmutableList<ExcludeMetadata> readVariantDependencyExcludes() throws IOException {
            ImmutableList.Builder<ExcludeMetadata> builder = new ImmutableList.Builder<ExcludeMetadata>();
            int len = readCount();
            for (int i = 0; i < len; i++) {
                String group = readString();
                String module = readString();
                builder.add(excludeRuleConverter.createExcludeRule(group, module));
            }
            return builder.build();
        }

        private void readVariantFiles(MutableComponentVariant variant) throws IOException {
            int count = decoder.readSmallInt();
            for (int i = 0; i < count; i++) {
                variant.addFile(decoder.readString(), decoder.readString());
            }
        }

        private void readVariantCapabilities(MutableComponentVariant variant) throws IOException {
            int count = decoder.readSmallInt();
            for (int i = 0; i < count; i++) {
                variant.addCapability(decoder.readString(), decoder.readString(), decoder.readString());
            }
        }

        private MutableModuleComponentResolveMetadata readIvy() throws IOException {
            readInfoSection();
            Map<NamespaceId, String> extraAttributes = readExtraInfo();
            List<Configuration> configurations = readConfigurations();
            List<IvyDependencyDescriptor> dependencies = readIvyDependencies();
            List<Artifact> artifacts = readArtifacts();
            List<Exclude> excludes = readModuleExcludes();
            MutableIvyModuleResolveMetadata metadata = ivyMetadataFactory.create(id, dependencies, configurations, artifacts, excludes);
            readSharedInfo(metadata);
            String branch = readNullableString();
            metadata.setBranch(branch);
            metadata.setExtraAttributes(extraAttributes);
            metadata.setAttributes(attributes);
            readVariants(metadata);
            return metadata;
        }

        private void readInfoSection() throws IOException {
            id = readId();
            attributes = readAttributes();
        }

        private ModuleComponentIdentifier readId() throws IOException {
            return DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId(readString(), readString()), readString());
        }

        private Map<NamespaceId, String> readExtraInfo() throws IOException {
            int len = readCount();
            Map<NamespaceId, String> result = new LinkedHashMap<NamespaceId, String>(len);
            for (int i = 0; i < len; i++) {
                NamespaceId namespaceId = new NamespaceId(readString(), readString());
                String value = readString();
                result.put(namespaceId, value);
            }
            return result;
        }

        private List<Configuration> readConfigurations() throws IOException {
            int len = readCount();
            List<Configuration> configurations = new ArrayList<Configuration>(len);
            for (int i = 0; i < len; i++) {
                Configuration configuration = readConfiguration();
                configurations.add(configuration);
            }
            return configurations;
        }

        private Configuration readConfiguration() throws IOException {
            String name = readString();
            boolean transitive = readBoolean();
            boolean visible = readBoolean();
            List<String> extendsFrom = readStringList();
            return new Configuration(name, transitive, visible, extendsFrom);
        }

        private List<Artifact> readArtifacts() throws IOException {
            int size = readCount();
            List<Artifact> result = Lists.newArrayListWithCapacity(size);
            for (int i = 0; i < size; i++) {
                IvyArtifactName ivyArtifactName = new DefaultIvyArtifactName(readString(), readString(), readNullableString(), readNullableString());
                result.add(new Artifact(ivyArtifactName, readStringSet()));
            }
            return result;
        }

        private List<IvyDependencyDescriptor> readIvyDependencies() throws IOException {
            int len = readCount();
            List<IvyDependencyDescriptor> result = Lists.newArrayListWithCapacity(len);
            for (int i = 0; i < len; i++) {
                result.add(readIvyDependency());
            }
            return result;
        }

        private IvyDependencyDescriptor readIvyDependency() throws IOException {
            ModuleComponentSelector requested = componentSelectorSerializer.read(decoder);
            SetMultimap<String, String> configMappings = readDependencyConfigurationMapping();
            List<Artifact> artifacts = readDependencyArtifactDescriptors();
            List<Exclude> excludes = readDependencyExcludes();
            String dynamicConstraintVersion = readString();
            boolean changing = readBoolean();
            boolean transitive = readBoolean();
            boolean optional = readBoolean();
            return new IvyDependencyDescriptor(requested, dynamicConstraintVersion, changing, transitive,  optional, configMappings, artifacts, excludes);
        }

        private SetMultimap<String, String> readDependencyConfigurationMapping() throws IOException {
            int size = readCount();
            SetMultimap<String, String> result = LinkedHashMultimap.create();
            for (int i = 0; i < size; i++) {
                String from = readString();
                Set<String> to = readStringSet();
                result.putAll(from, to);
            }
            return result;
        }

        private List<Artifact> readDependencyArtifactDescriptors() throws IOException {
            int size = readCount();
            List<Artifact> result = Lists.newArrayListWithCapacity(size);
            for (int i = 0; i < size; i++) {
                IvyArtifactName ivyArtifactName = new DefaultIvyArtifactName(readString(), readString(), readNullableString(), readNullableString());
                result.add(new Artifact(ivyArtifactName, readStringSet()));
            }
            return result;
        }

        private List<Exclude> readDependencyExcludes() throws IOException {
            int len = readCount();
            List<Exclude> result = Lists.newArrayListWithCapacity(len);
            for (int i = 0; i < len; i++) {
                DefaultExclude rule = readExcludeRule();
                result.add(rule);
            }
            return result;
        }

        private List<Exclude> readModuleExcludes() throws IOException {
            int len = readCount();
            List<Exclude> result = new ArrayList<Exclude>(len);
            for (int i = 0; i < len; i++) {
                result.add(readExcludeRule());
            }
            return result;
        }

        private DefaultExclude readExcludeRule() throws IOException {
            String moduleOrg = readString();
            String moduleName = readString();
            IvyArtifactName artifactName = readNullableArtifact();
            String[] confs = readStringArray();
            String matcher = readNullableString();
            return new DefaultExclude(moduleIdentifierFactory.module(moduleOrg, moduleName), artifactName, confs, matcher);
        }

        private IvyArtifactName readNullableArtifact() throws IOException {
            boolean hasArtifact = readBoolean();
            IvyArtifactName artifactName = null;
            if (hasArtifact) {
                String artifact = readString();
                String type = readString();
                String ext = readNullableString();
                String classifier = readNullableString();
                artifactName = new DefaultIvyArtifactName(artifact, type, ext, classifier);
            }
            return artifactName;
        }

        private List<MavenDependencyDescriptor> readMavenDependencies() throws IOException {
            int len = readCount();
            List<MavenDependencyDescriptor> result = Lists.newArrayListWithCapacity(len);
            for (int i = 0; i < len; i++) {
                result.add(readMavenDependency());
            }
            return result;
        }

        private MavenDependencyDescriptor readMavenDependency() throws IOException {
            ModuleComponentSelector requested = componentSelectorSerializer.read(decoder);
            IvyArtifactName artifactName = readNullableArtifact();
            List<ExcludeMetadata> mavenExcludes = readMavenDependencyExcludes();
            MavenScope scope = MavenScope.values()[decoder.readSmallInt()];
            boolean optional = decoder.readBoolean();
            return new MavenDependencyDescriptor(scope, optional, requested, artifactName, mavenExcludes);
        }

        private List<ExcludeMetadata> readMavenDependencyExcludes() throws IOException {
            int len = readCount();
            List<ExcludeMetadata> result = Lists.newArrayListWithCapacity(len);
            for (int i = 0; i < len; i++) {
                String moduleOrg = readString();
                String moduleName = readString();
                DefaultExclude rule = new DefaultExclude(moduleIdentifierFactory.module(moduleOrg, moduleName));
                result.add(rule);
            }
            return result;
        }

        private int readCount() throws IOException {
            return decoder.readSmallInt();
        }

        private String readString() throws IOException {
            return decoder.readString();
        }

        private String readNullableString() throws IOException {
            return decoder.readNullableString();
        }

        private boolean readBoolean() throws IOException {
            return decoder.readBoolean();
        }

        private String[] readStringArray() throws IOException {
            int size = readCount();
            String[] array = new String[size];
            for (int i = 0; i < size; i++) {
                array[i] = readNullableString();
            }
            return array;
        }

        private List<String> readStringList() throws IOException {
            int size = readCount();
            List<String> list = new ArrayList<String>(size);
            for (int i = 0; i < size; i++) {
                list.add(readString());
            }
            return list;
        }

        private Set<String> readStringSet() throws IOException {
            int size = readCount();
            Set<String> set = new LinkedHashSet<String>(3 * size / 2, 0.9f);
            for (int i = 0; i < size; i++) {
                set.add(readString());
            }
            return set;
        }
    }

}
