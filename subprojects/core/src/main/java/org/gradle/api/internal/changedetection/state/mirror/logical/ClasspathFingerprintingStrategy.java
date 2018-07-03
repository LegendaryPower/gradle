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

package org.gradle.api.internal.changedetection.state.mirror.logical;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import org.gradle.api.internal.changedetection.state.FileContentSnapshot;
import org.gradle.api.internal.changedetection.state.FileHashSnapshot;
import org.gradle.api.internal.changedetection.state.IgnoredPathFileSnapshot;
import org.gradle.api.internal.changedetection.state.IndexedNormalizedFileSnapshot;
import org.gradle.api.internal.changedetection.state.JarHasher;
import org.gradle.api.internal.changedetection.state.NormalizedFileSnapshot;
import org.gradle.api.internal.changedetection.state.ResourceHasher;
import org.gradle.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import org.gradle.api.internal.changedetection.state.mirror.PhysicalSnapshot;
import org.gradle.api.internal.changedetection.state.mirror.PhysicalSnapshotVisitor;
import org.gradle.api.internal.changedetection.state.mirror.RelativePathTracker;
import org.gradle.caching.internal.DefaultBuildCacheHasher;
import org.gradle.internal.FileUtils;
import org.gradle.internal.file.FileType;
import org.gradle.internal.hash.HashCode;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;

public class ClasspathFingerprintingStrategy implements FingerprintingStrategy {

    private final boolean includeOnlyJars;
    private final ResourceSnapshotterCacheService cacheService;
    private final ResourceHasher classpathResourceHasher;
    private final JarHasher jarHasher;
    private final HashCode jarHasherConfigurationHash;

    public ClasspathFingerprintingStrategy(boolean includeOnlyJars, ResourceHasher classpathResourceHasher, ResourceSnapshotterCacheService cacheService) {
        this.includeOnlyJars = includeOnlyJars;
        this.cacheService = cacheService;
        this.classpathResourceHasher = classpathResourceHasher;
        this.jarHasher = new JarHasher(classpathResourceHasher);
        DefaultBuildCacheHasher hasher = new DefaultBuildCacheHasher();
        jarHasher.appendConfigurationToHasher(hasher);
        this.jarHasherConfigurationHash = hasher.hash();
    }

    @Override
    public Map<String, NormalizedFileSnapshot> collectSnapshots(Iterable<PhysicalSnapshot> roots) {
        final ImmutableMap.Builder<String, NormalizedFileSnapshot> builder = ImmutableMap.builder();
        final HashSet<String> processedEntries = new HashSet<String>();
        for (PhysicalSnapshot root : roots) {
            final ImmutableSortedMap.Builder<String, NormalizedFileSnapshot> rootBuilder = ImmutableSortedMap.naturalOrder();
            root.accept(new ClasspathSnapshottingVisitor(new PhysicalSnapshotVisitor() {
                private boolean root = true;
                private int rootIndex;

                @Override
                public boolean preVisitDirectory(String absolutePath, String name) {
                    if (root) {
                        rootIndex = absolutePath.length() + 1;
                    }
                    root = false;
                    return true;
                }

                @Override
                public void visit(String absolutePath, String name, FileContentSnapshot content) {
                    if (root) {
                        rootIndex = absolutePath.length() + 1;
                    }
                    if (processedEntries.add(absolutePath)) {
                        NormalizedFileSnapshot normalizedFileSnapshot = root ? new IgnoredPathFileSnapshot(content) : new IndexedNormalizedFileSnapshot(absolutePath, getIndex(name), content);
                        rootBuilder.put(
                            absolutePath,
                            normalizedFileSnapshot);
                    }
                }

                private int getIndex(String name) {
                    return root ? rootIndex - 1 - name.length() : rootIndex;
                }

                @Override
                public void postVisitDirectory() {
                }
            }));
            builder.putAll(rootBuilder.build());
        }
        return builder.build();
    }

    private class ClasspathSnapshottingVisitor implements PhysicalSnapshotVisitor {

        private final PhysicalSnapshotVisitor delegate;
        private RelativePathTracker relativePathTracker = new RelativePathTracker();

        public ClasspathSnapshottingVisitor(PhysicalSnapshotVisitor delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean preVisitDirectory(String absolutePath, String name) {
            relativePathTracker.enter(name);
            return delegate.preVisitDirectory(absolutePath, name);
        }

        @Override
        public void visit(String absolutePath, String name, FileContentSnapshot content) {
            if (content.getType() == FileType.RegularFile) {
                FileContentSnapshot newContent = fingerprintFile(absolutePath, name, content);
                if (newContent != null) {
                    delegate.visit(absolutePath, name, newContent);
                }
            }
        }

        @Nullable
        private FileContentSnapshot fingerprintFile(String absolutePath, String name, FileContentSnapshot content) {
            return relativePathTracker.isRoot() ? fingerprintRootFile(absolutePath, name, content) : fingerprintTreeFile(absolutePath, name, content);
        }

        @Nullable
        private FileContentSnapshot fingerprintTreeFile(String absolutePath, String name, FileContentSnapshot content) {
            relativePathTracker.enter(name);
            HashCode newHash = classpathResourceHasher.hash(absolutePath, relativePathTracker.getRelativePath(), content);
            relativePathTracker.leave();
            return newHash == null ? null : new FileHashSnapshot(newHash);
        }

        @Override
        public void postVisitDirectory() {
            relativePathTracker.leave();
            delegate.postVisitDirectory();
        }
    }

    @Nullable
    private FileContentSnapshot fingerprintRootFile(String absolutePath, String name, FileContentSnapshot content) {
        if (FileUtils.hasExtensionIgnoresCase(name, ".jar")) {
            return snapshotJarContents(absolutePath, ImmutableList.of(name), content);
        }
        return includeOnlyJars ? null: content;
    }

    @Nullable
    private FileContentSnapshot snapshotJarContents(String absolutePath, Iterable<String> relativePath, FileContentSnapshot contentSnapshot) {
        HashCode hash = cacheService.hashFile(absolutePath, relativePath, contentSnapshot, jarHasher, jarHasherConfigurationHash);
        return hash == null ? null : new FileHashSnapshot(hash);
    }

    @Override
    public FingerprintCompareStrategy getCompareStrategy() {
        return FingerprintCompareStrategy.CLASSPATH;
    }
}
