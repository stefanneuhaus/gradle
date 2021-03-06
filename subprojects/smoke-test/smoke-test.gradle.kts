/*
 * Copyright 2019 the original author or authors.
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
import accessors.groovy
import org.gradle.gradlebuild.BuildEnvironment
import org.gradle.gradlebuild.test.integrationtests.SmokeTest
import org.gradle.gradlebuild.test.integrationtests.defaultGradleGeneratedApiJarCacheDirProvider
import org.gradle.gradlebuild.unittestandcompile.ModuleType
import org.gradle.gradlebuild.versioning.DetermineCommitId
import org.gradle.testing.performance.generator.tasks.RemoteProject

plugins {
    `java-library`
}

gradlebuildJava {
    moduleType = ModuleType.INTERNAL
}

val smokeTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val smokeTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val smokeTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}
val smokeTestCompileClasspath: Configuration by configurations.getting
val smokeTestRuntimeClasspath: Configuration by configurations.getting

configurations {
    partialDistribution.get().extendsFrom(
        get(smokeTest.runtimeClasspathConfigurationName)
    )
}

dependencies {
    smokeTestImplementation(project(":baseServices"))
    smokeTestImplementation(project(":coreApi"))
    smokeTestImplementation(project(":testKit"))
    smokeTestImplementation(project(":internalIntegTesting"))
    smokeTestImplementation(project(":launcher"))
    smokeTestImplementation(project(":persistentCache"))
    smokeTestImplementation(project(":jvmServices"))
    smokeTestImplementation(library("commons_io"))
    smokeTestImplementation(library("jgit"))
    smokeTestImplementation(library("gradleProfiler")) {
        because("Using build mutators to change a Java file")
    }
    smokeTestImplementation(testLibrary("spock"))

    val allTestRuntimeDependencies: DependencySet by rootProject.extra
    allTestRuntimeDependencies.forEach {
        smokeTestRuntimeOnly(it)
    }

    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":versionControl")))
}

fun SmokeTest.configureForSmokeTest() {
    group = "Verification"
    testClassesDirs = smokeTest.output.classesDirs
    classpath = smokeTest.runtimeClasspath
    maxParallelForks = 1 // those tests are pretty expensive, we shouldn"t execute them concurrently
    gradleInstallationForTest.gradleGeneratedApiJarCacheDir.set(
        defaultGradleGeneratedApiJarCacheDirProvider()
    )
}

tasks.register<SmokeTest>("smokeTest") {
    description = "Runs Smoke tests"
    configureForSmokeTest()
}

tasks.register<SmokeTest>("instantSmokeTest") {
    description = "Runs Smoke tests with instant execution"
    configureForSmokeTest()
    systemProperty("org.gradle.integtest.executer", "instant")
}

plugins.withType<IdeaPlugin>().configureEach {
    model.module {
        testSourceDirs = testSourceDirs + smokeTest.groovy.srcDirs
        testResourceDirs = testResourceDirs + smokeTest.resources.srcDirs
        scopes["TEST"]!!["plus"]!!.add(smokeTestCompileClasspath)
        scopes["TEST"]!!["plus"]!!.add(smokeTestRuntimeClasspath)
    }
}

plugins.withType<EclipsePlugin>().configureEach {
    eclipse.classpath {
        plusConfigurations.add(smokeTestCompileClasspath)
        plusConfigurations.add(smokeTestRuntimeClasspath)
    }
}

// TODO Copied from instant-execution.gradle.kts, we should have one place to clone this thing and clone it from there locally when needed
tasks {

    register<RemoteProject>("santaTracker") {
        remoteUri.set("https://github.com/gradle/santa-tracker-android.git")
        // From branch agp-3.6.0
        ref.set("3bbbd895de38efafd0dd1789454d4e4cb72d46d5")
    }

    register<RemoteProject>("gradleBuildCurrent") {
        remoteUri.set(rootDir.absolutePath)
        ref.set(rootProject.tasks.named<DetermineCommitId>("determineCommitId").flatMap { it.determinedCommitId })
    }

    val remoteProjects = withType<RemoteProject>()

    if (BuildEnvironment.isCiServer) {
        remoteProjects.configureEach {
            outputs.upToDateWhen { false }
        }
    }

    register<Delete>("cleanRemoteProjects") {
        delete(remoteProjects.map { it.outputDirectory })
    }

    withType<SmokeTest>().configureEach {
        dependsOn(remoteProjects)
        inputs.property("androidHomeIsSet", System.getenv("ANDROID_HOME") != null)
    }
}
