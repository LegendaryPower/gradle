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

package org.gradle.integtests.resolve

import org.gradle.integtests.fixtures.GradleMetadataResolveRunner
import org.gradle.integtests.fixtures.RequiredFeature
import org.gradle.integtests.fixtures.RequiredFeatures
import org.gradle.util.ToBeImplemented
import spock.lang.Ignore

class ComponentMetadataRulesCachingIntegrationTest extends AbstractModuleDependencyResolveTest implements ComponentMetadataRulesSupport {
    String getDefaultStatus() {
        GradleMetadataResolveRunner.useIvy()?'integration':'release'
    }

    def setup() {
        buildFile << """
dependencies {
    conf 'org.test:projectA:1.0'
}

// implement Sync manually to make sure that task is never up-to-date
task resolve {
    doLast {
        delete 'libs'
        copy {
            from configurations.conf
            into 'libs'
        }
    }
}
"""
    }

    def "rule is cached across builds"() {
        repository {
            'org.test:projectA:1.0'()
        }
        buildFile << """

@CacheableRule
class CachedRule implements ComponentMetadataRule {
    public void execute(ComponentMetadataContext context) {
            println 'Rule executed'
    }
}

dependencies {
    components {
        all(CachedRule)
    }
}
"""

        when:
        repositoryInteractions {
            'org.test:projectA:1.0' {
                allowAll()
            }
        }

        then:
        succeeds 'resolve'
        outputContains('Rule executed')


        then:
        succeeds 'resolve'
        outputDoesNotContain('Rule executed')
    }

    def 'rule cache properly differentiates inputs'() {
        repository {
            'org.test:projectA:1.0'()
        }
        buildFile << """

@CacheableRule
class CachedRuleA implements ComponentMetadataRule {
    public void execute(ComponentMetadataContext context) {
            println 'Rule A executed'
            context.details.changing = true
    }
}

@CacheableRule
class CachedRuleB implements ComponentMetadataRule {
    public void execute(ComponentMetadataContext context) {
            println 'Rule B executed - saw changing ' + context.details.changing
    }
}

dependencies {
    components {
        if (project.hasProperty('cacheA')) {
            all(CachedRuleA)
        }
        all(CachedRuleB)
    }
}
"""
        when:
        repositoryInteractions {
            'org.test:projectA:1.0' {
                allowAll()
            }
        }

        then:
        succeeds 'resolve'
        outputContains('Rule B executed - saw changing false')


        then:
        succeeds 'resolve', '-PcacheA'
        outputContains('Rule A executed')
        outputContains('Rule B executed - saw changing true')
    }

    def 'can cache rules with service injection'() {
        repository {
            'org.test:projectA:1.0'()
        }
        buildFile << """

import javax.inject.Inject
import org.gradle.api.artifacts.repositories.RepositoryResourceAccessor

@CacheableRule
class CachedRuleA implements ComponentMetadataRule {

    RepositoryResourceAccessor accessor

    @Inject
    CachedRuleA(RepositoryResourceAccessor accessor) {
        this.accessor = accessor
    }
    
    void execute(ComponentMetadataContext context) {
            println 'Rule A executed'
            context.details.changing = true
    }
}

@CacheableRule
class CachedRuleB implements ComponentMetadataRule {

    RepositoryResourceAccessor accessor

    @Inject
    CachedRuleB(RepositoryResourceAccessor accessor) {
        this.accessor = accessor
    }
    
    public void execute(ComponentMetadataContext context) {
            println 'Rule B executed - saw changing ' + context.details.changing
    }
}

dependencies {
    components {
        if (project.hasProperty('cacheA')) {
            all(CachedRuleA)
        }
        all(CachedRuleB)
    }
}
"""
        when:
        repositoryInteractions {
            'org.test:projectA:1.0' {
                allowAll()
            }
        }

        then:
        succeeds 'resolve'
        outputContains('Rule B executed - saw changing false')


        and:
        succeeds 'resolve', '-PcacheA'
        outputContains('Rule A executed')
        outputContains('Rule B executed - saw changing true')
    }

    def 'can cache rules having a custom type attribute as parameter'() {
        repository {
            'org.test:projectA:1.0'()
        }
        buildFile << """

import javax.inject.Inject

@CacheableRule
class AttributeCachedRule implements ComponentMetadataRule {

    Attribute targetAttribute

    @Inject
    AttributeCachedRule(Attribute attribute) {
        this.targetAttribute = attribute
    }
    
    void execute(ComponentMetadataContext context) {
        println 'Attribute rule executed'
    }
}

class Thing implements Named, Serializable { 
    String name
    
    int hashCode() {
        return name.hashCode()
    }
    
    boolean equals(Object other) {
        if (other instanceof Thing) {
            return ((Thing)other).name.equals(name)
        }
        return false;
    }
    
    String toString() {
        return 'Thing[' + name + ']'
    }
}

def thing = Attribute.of(Thing)

dependencies {
    components {
        all(AttributeCachedRule) {
            params(thing)
        }
    }
}
"""
        when:
        repositoryInteractions {
            'org.test:projectA:1.0' {
                allowAll()
            }
        }

        then:
        succeeds 'resolve'
        outputContains('Attribute rule executed')

        and:
        succeeds 'resolve'
        outputDoesNotContain('Attribute rule executed')
    }

    @Ignore
    @ToBeImplemented
    @RequiredFeatures(
        @RequiredFeature(feature = GradleMetadataResolveRunner.GRADLE_METADATA, value = "true")
    )
    def 'can cache rules setting custom type attributes'() {
        repository {
            'org.test:projectA:1.0'()
        }

        def expectedStatus = useIvy() ? 'integration' : 'release'

        buildFile << """

import javax.inject.Inject

@CacheableRule
class AttributeCachedRule implements ComponentMetadataRule {

    Attribute targetAttribute

    @Inject
    AttributeCachedRule(Attribute attribute) {
        this.targetAttribute = attribute
    }
    
    void execute(ComponentMetadataContext context) {
        println 'Attribute rule executed'
        context.details.withVariant('api') {
            attributes {
                attribute(targetAttribute, new Thing(name: 'Foo'))
            }
        }
        context.details.withVariant('runtime') {
            attributes {
                attribute(targetAttribute, new Thing(name: 'Bar'))
            }
        }
    }
}

class Thing implements Named, Serializable { 
    String name
    
    int hashCode() {
        return name.hashCode()
    }
    
    boolean equals(Object other) {
        if (other instanceof Thing) {
            return ((Thing)other).name.equals(name)
        }
        return false;
    }
    
    String toString() {
        return 'Thing[' + name + ']'
    }
}

def thing = Attribute.of(Thing)

configurations {
    conf {
        attributes {
            attribute thing, new Thing(name: 'Bar')
        }
    }
}

dependencies {
    components {
        all(AttributeCachedRule) {
            params(thing)
        }
    }
}
"""
        when:
        repositoryInteractions {
            'org.test:projectA:1.0' {
                allowAll()
            }
        }

        then:
        succeeds 'checkDeps'
        outputContains('Attribute rule executed')
        resolve.expectGraph {
            root(":", ":test:") {
                module('org.test:projectA:1.0') {
                    variant('runtime', ['org.gradle.status': expectedStatus, 'org.gradle.usage' : 'java-runtime', 'thing' : 'Thing[Bar]'])
                }
            }
        }


        and:
        succeeds 'checkDeps'
        outputDoesNotContain('Attribute rule executed')
        resolve.expectGraph {
            root(":", ":test:") {
                module('org.test:projectA:1.0') {
                    variant('runtime', ['org.gradle.status': expectedStatus, 'org.gradle.usage' : 'java-runtime', 'thing' : 'Thing[Bar]'])
                }
            }
        }
    }
}
