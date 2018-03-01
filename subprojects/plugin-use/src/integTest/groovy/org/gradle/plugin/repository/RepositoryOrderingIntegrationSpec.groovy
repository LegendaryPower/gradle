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
package org.gradle.plugin.repository

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Issue

import static org.gradle.api.internal.artifacts.BaseRepositoryFactory.PLUGIN_PORTAL_OVERRIDE_URL_PROPERTY


class RepositoryOrderingIntegrationSpec extends AbstractIntegrationSpec {

    @Issue("gradle/gradle#4310")
    def "buildscript repositories searched before plugin management repositories"() {

        given:
        def pluginPortalUri = normalizedUriOf("pluginPortalRepo")
        def buildscriptRepoUri = normalizedUriOf("buildscriptRepo")
        def pluginManagementRepoUri = normalizedUriOf("pluginManagementRepo")
        overridePluginPortalUri(pluginPortalUri)

        and:
        buildScript """
            buildscript {
                repositories { maven { url "$buildscriptRepoUri" } }
                dependencies { classpath "my:plugin:1.0" }
            }

            plugins {
                id 'base'
            }

            apply plugin: 'my-plugin'
        """.stripIndent()

        when:
        fails "tasks"

        then: "searched buildscript repository then plugin portal"
        errorOutput.contains """
            > Could not resolve all artifacts for configuration ':classpath'.
               > Could not find my:plugin:1.0.
                 Searched in the following locations:
                     $buildscriptRepoUri/my/plugin/1.0/plugin-1.0.pom
                     $buildscriptRepoUri/my/plugin/1.0/plugin-1.0.jar
                     $pluginPortalUri/my/plugin/1.0/plugin-1.0.pom
                     $pluginPortalUri/my/plugin/1.0/plugin-1.0.jar
        """.stripIndent()

        when:
        settingsFile << """
            pluginManagement {
                repositories { maven { url = "$pluginManagementRepoUri" } }
            }
        """.stripIndent()

        and:
        fails "tasks"

        then: "searched buildscript repository then plugin management repositories"
        errorOutput.contains """
            > Could not resolve all artifacts for configuration ':classpath'.
               > Could not find my:plugin:1.0.
                 Searched in the following locations:
                     $buildscriptRepoUri/my/plugin/1.0/plugin-1.0.pom
                     $buildscriptRepoUri/my/plugin/1.0/plugin-1.0.jar
                     $pluginManagementRepoUri/my/plugin/1.0/plugin-1.0.pom
                     $pluginManagementRepoUri/my/plugin/1.0/plugin-1.0.jar
        """.stripIndent()
    }

    private String normalizedUriOf(String path) {
        file(path).toURI().toString().replace("%20", " ")
    }

    private void overridePluginPortalUri(String uri) {
        executer.withArgument("-D${PLUGIN_PORTAL_OVERRIDE_URL_PROPERTY}=$uri")
    }
}
