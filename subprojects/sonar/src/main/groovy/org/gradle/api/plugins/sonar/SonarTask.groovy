/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.api.plugins.sonar

import org.sonar.batch.bootstrapper.Bootstrapper
import org.gradle.api.internal.ConventionTask
import org.gradle.api.tasks.TaskAction
import org.gradle.util.ClasspathUtil
import org.gradle.api.plugins.sonar.model.SonarProject
import org.gradle.api.plugins.sonar.model.SonarModel

/**
 * Analyzes a project and stores the results in the Sonar database.
 */
class SonarTask extends ConventionTask {
    /**
     * Global configuration for the Sonar analysis.
     */
    SonarModel sonarModel
    /**
     * Project configuration for the Sonar analysis.
     */
    SonarProject sonarProject

    @TaskAction
    void analyze() {
        sonarModel.bootstrapDir.mkdirs()
        def bootstrapper = new Bootstrapper("Gradle", sonarModel.server.url, sonarModel.bootstrapDir)

        def classLoader = bootstrapper.createClassLoader(
                [findGradleSonarJar()] as URL[], SonarTask.classLoader,
                        "groovy", "org.codehaus.groovy", "org.slf4j", "org.apache.log4j", "org.apache.commons.logging",
                                "org.gradle.api.plugins.sonar.model")

        def analyzerClass = classLoader.loadClass("org.gradle.api.plugins.sonar.internal.SonarCodeAnalyzer")
        def analyzer = analyzerClass.newInstance()
        analyzer.sonarModel = sonarModel
        analyzer.sonarProject = sonarProject
        analyzer.execute()
    }

    protected URL findGradleSonarJar() {
        def url = ClasspathUtil.getClasspath(SonarTask.classLoader).find { it.path.contains("gradle-sonar") }
        assert url != null, "failed to detect file system location of gradle-sonar Jar"
        url
    }
}