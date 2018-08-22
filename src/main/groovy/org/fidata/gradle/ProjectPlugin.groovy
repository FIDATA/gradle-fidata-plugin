#!/usr/bin/env groovy
/*
 * org.fidata.project Gradle plugin
 * Copyright © 2017-2018  Basil Peace
 *
 * This file is part of gradle-base-plugins.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.fidata.gradle

import static org.gradle.language.base.plugins.LifecycleBasePlugin.CHECK_TASK_NAME
import static org.gradle.language.base.plugins.LifecycleBasePlugin.BUILD_TASK_NAME
import static org.ajoberstar.gradle.git.release.base.BaseReleasePlugin.RELEASE_TASK_NAME
import static org.gradle.api.plugins.ProjectReportsPlugin.PROJECT_REPORT
import static org.gradle.initialization.DefaultSettings.DEFAULT_BUILD_SRC_DIR
import static org.gradle.api.Project.DEFAULT_BUILD_DIR_NAME
import static org.gradle.internal.FileUtils.toSafeFileName
import static org.fidata.gradle.utils.VersionUtils.isPreReleaseVersion
import static org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP
import static com.dorongold.gradle.tasktree.TaskTreePlugin.TASK_TREE_TASK_NAME
import org.gradle.tooling.UnsupportedVersionException
import org.fidata.gradle.utils.PathDirector
import org.fidata.gradle.utils.ReportPathDirectorException
import java.nio.file.Path
import java.nio.file.Paths
import org.gradle.api.tasks.TaskCollection
import org.ajoberstar.grgit.auth.AuthConfig
import org.ajoberstar.grgit.Grgit
import de.gliderpilot.gradle.semanticrelease.SemanticReleasePluginExtension
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.fidata.gradle.tasks.CodeNarcTaskConvention
import org.gradle.api.file.ConfigurableFileTree
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.fidata.gradle.internal.AbstractProjectPlugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.testing.Test
import org.fidata.gradle.tasks.NoJekyll
import org.fidata.gradle.tasks.ResignGitCommit
import org.gradle.api.plugins.quality.CodeNarc
import org.fidata.gradle.utils.PluginDependeesUtils
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.quality.CodeNarcExtension
import org.gradle.util.GradleVersion
import org.gradle.api.plugins.ProjectReportsPluginConvention
import org.gradle.api.tasks.diagnostics.BuildEnvironmentReportTask
import org.gradle.api.reporting.components.ComponentReport
import org.gradle.api.tasks.diagnostics.DependencyReportTask
import org.gradle.api.tasks.diagnostics.DependencyInsightReportTask
import org.gradle.api.reporting.dependents.DependentComponentsReport
import org.gradle.api.reporting.model.ModelReport
import org.gradle.api.tasks.diagnostics.ProjectReportTask
import org.gradle.api.tasks.diagnostics.PropertyReportTask
import org.gradle.api.reporting.dependencies.HtmlDependencyReportTask
import org.gradle.api.tasks.diagnostics.TaskReportTask
import org.fidata.gradle.tasks.InputsOutputs
import org.gradle.api.artifacts.ComponentMetadataDetails
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.file.FileTreeElement
import groovy.text.StreamingTemplateEngine
import groovy.text.Template
import org.gradle.api.logging.LogLevel
import cz.malohlava.VisTegPluginExtension
import org.gradle.api.tasks.wrapper.Wrapper
import org.gradle.api.reporting.ReportingExtension
import org.ajoberstar.gradle.git.publish.GitPublishExtension
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.gradle.api.artifacts.ComponentSelectionRules
import org.gradle.api.artifacts.ResolutionStrategy
import de.gliderpilot.gradle.semanticrelease.UpdateGithubRelease
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention
import com.dorongold.gradle.tasktree.TaskTreeTask
import java.util.regex.Matcher
import java.util.regex.Pattern
import com.github.zafarkhaja.semver.Version
import org.gradle.api.tasks.TaskProvider

/**
 * Provides an environment for a general, language-agnostic project
 */
@CompileStatic
final class ProjectPlugin extends AbstractProjectPlugin {
  /**
   * Name of fidata convention for {@link Project}
   */
  public static final String FIDATA_CONVENTION_NAME = 'fidata'

  static final Template COMMIT_MESSAGE_TEMPLATE = new StreamingTemplateEngine().createTemplate(
    '''
      $type: $subject

      Generated by $generatedBy
    '''.stripIndent()
  )

  /**
   * List of filenames considered as license files
   */
  public static final List<String> LICENSE_FILE_NAMES = [
    // License file names recognized by JFrog Artifactory
    'license',
    'LICENSE',
    'license.txt',
    'LICENSE.txt',
    'LICENSE.TXT',
    // GPL standard file names
    'COPYING',
    'COPYING.LESSER',
  ]

  /**
   * Minimum supported version of Gradle
   */
  public static final String GRADLE_MINIMUM_SUPPORTED_VERSION = '4.8'

  @PackageScope
  String defaultProjectGroup = 'org.fidata'

  @Override
  @SuppressWarnings('CouldBeElvis')
  void apply(Project project) {
    if (GradleVersion.current() < GradleVersion.version(GRADLE_MINIMUM_SUPPORTED_VERSION)) {
      throw new UnsupportedVersionException("Gradle versions before $GRADLE_MINIMUM_SUPPORTED_VERSION are not supported")
    }

    super.apply(project)

    PluginDependeesUtils.applyPlugins project, ProjectPluginDependees.PLUGIN_DEPENDEES

    project.convention.plugins.put FIDATA_CONVENTION_NAME, new ProjectConvention(project)

    if (!project.group) { project.group = "${ -> defaultProjectGroup }" }

    project.extensions.configure(ReportingExtension) { ReportingExtension extension ->
      extension.baseDir = project.convention.getPlugin(ProjectConvention).reportsDir
    }
    // project.extensions.getByType(ReportingExtension).baseDir = project.convention.getPlugin(ProjectConvention).reportsDir

    configureGit()

    configureLifecycle()

    configurePrerequisitesLifecycle()

    configureArtifactory()

    configureDependencyResolution()

    configureDocumentation()

    configureArtifactPublishing()

    configureCodeQuality()

    configureDiagnostics()
  }

  /**
   * Release task group name
   */
  public static final String RELEASE_TASK_GROUP_NAME = 'Release'

  private void configureLifecycle() {
    project.tasks.named(BUILD_TASK_NAME).configure { Task build ->
      build.dependsOn.remove CHECK_TASK_NAME
    }
    project.tasks.named(RELEASE_TASK_NAME).configure { Task release ->
      release.with {
        group = RELEASE_TASK_GROUP_NAME
        dependsOn project.tasks.named(BUILD_TASK_NAME) // TODO
        dependsOn project.tasks.named(CHECK_TASK_NAME)
      }
    }
    project.tasks.named(CHECK_TASK_NAME).configure { Task check ->
      check.dependsOn check.project.tasks.withType(Test)
    }

    project.extensions.getByType(SemanticReleasePluginExtension).branchNames.replace 'develop', ''

    project.tasks.withType(UpdateGithubRelease).named('updateGithubRelease').configure { UpdateGithubRelease updateGithubRelease ->
      updateGithubRelease.repo.ghToken = project.extensions.extraProperties['ghToken'].toString()
    }
  }

  private void configurePrerequisitesLifecycle() {
    project.tasks.withType(DependencyUpdatesTask).configureEach { DependencyUpdatesTask dependencyUpdates ->
      dependencyUpdates.group = null
      dependencyUpdates.revision = 'release'
      dependencyUpdates.outputFormatter = 'xml'
      dependencyUpdates.outputDir = project.convention.getPlugin(ProjectConvention).getXmlReportDir(Paths.get('dependencyUpdates')).toString()
      dependencyUpdates.resolutionStrategy = { ResolutionStrategy resolutionStrategy ->
        resolutionStrategy.componentSelection { ComponentSelectionRules rules ->
          rules.all { ComponentSelection selection ->
            if (dependencyUpdates.revision == 'release' && isPreReleaseVersion(selection.candidate.version)) {
              selection.reject 'Pre-release version'
            }
          }
        }
      }
    }

    project.tasks.withType(Wrapper).configureEach { Wrapper wrapper ->
      wrapper.with {
        if (name == 'wrapper') {
          gradleVersion = '4.9'
        }
      }
    }
  }

  /**
   * URL of FIDATA Artifactory
   */
  public static final String ARTIFACTORY_URL = 'https://fidata.jfrog.io/fidata'

  private void configureArtifactory() {
    project.convention.getPlugin(ArtifactoryPluginConvention).contextUrl = ARTIFACTORY_URL
  }

  private void configureDependencyResolution() {
    project.repositories.maven { MavenArtifactRepository mavenArtifactRepository ->
      mavenArtifactRepository.with {
        /*
         * WORKAROUND:
         * Groovy bug?
         * When GString is used, URI property setter is called anyway, and we got cast error
         * <grv87 2018-06-26>
         */
        url = project.uri("$ARTIFACTORY_URL/libs-${ project.convention.getPlugin(ProjectConvention).isRelease.get() ? 'release' : 'snapshot' }/")
        credentials.username = project.extensions.extraProperties['artifactoryUser']
        credentials.password = project.extensions.extraProperties['artifactoryPassword']
      }
    }

    project.configurations.all { Configuration configuration ->
      configuration.resolutionStrategy.cacheChangingModulesFor 0, 'seconds'
    }

    project.dependencies.components.all { ComponentMetadataDetails metadata ->
      metadata.with {
        if (status == 'release' && isPreReleaseVersion(id.version)) {
          status = 'milestone'
        }
      }
    }
  }

  private void configureArtifactPublishing() {
    /*
     * WORKAROUND:
     * https://github.com/gradle/gradle/issues/1918
     * Signing plugin doesn't support GPG 2 key IDs
     * <grv87 2018-07-01>
     */
    project.extensions.extraProperties['signing.keyId'] = project.extensions.extraProperties['gpgKeyId'].toString()[-8..-1]
    project.extensions.extraProperties['signing.password'] = project.extensions.extraProperties['gpgKeyPassword'].toString()
    project.extensions.extraProperties['signing.secretKeyRingFile'] = project.extensions.extraProperties['gpgSecretKeyRingFile'].toString()
  }

  private void configureGit() {
    System.setProperty AuthConfig.USERNAME_OPTION, project.extensions.extraProperties['gitUsername'].toString()
    System.setProperty AuthConfig.PASSWORD_OPTION, project.extensions.extraProperties['gitPassword'].toString()
  }

  /**
   * Name of NoJekyll task
   */
  public static final String NO_JEKYLL_TASK_NAME = 'noJekyll'

  private void configureDocumentation() {
    project.extensions.configure(GitPublishExtension) { GitPublishExtension extension ->
      extension.with {
        branch.set 'gh-pages'
        preserve.include '**'
        /*
         * CAVEAT:
         * SNAPSHOT documentation for other branches should be removed manually
         */
        preserve.exclude { FileTreeElement fileTreeElement ->
          Pattern snapshotSuffix = ~/-SNAPSHOT$/
          Matcher m = snapshotSuffix.matcher(fileTreeElement.relativePath.segments[0])
          if (!m.find()) {
            return false
          }
          String dirVersion = m.replaceFirst('')
          String projectVersion = project.version.toString().replaceFirst(snapshotSuffix, '')
          try {
            return Version.valueOf(dirVersion).preReleaseVersion == Version.valueOf(projectVersion).preReleaseVersion
          } catch (ignored) {
            return false
          }
        }
        commitMessage.set COMMIT_MESSAGE_TEMPLATE.make(
          type: 'docs',
          subject: "publish documentation for version ${ project.version }",
          generatedBy: 'org.ajoberstar:gradle-git-publish'
        ).toString()
      }
    }

    boolean repoClean = ((Grgit)project.extensions.extraProperties.get('grgit')).status().clean

    TaskProvider<Task> gitPublishCommitProvider = project.tasks.named(/* WORKAROUND: GitPublishPlugin.COMMIT_TASK has package scope <grv87 2018-06-23> */ 'gitPublishCommit')
    gitPublishCommitProvider.configure { Task gitPublishCommit ->
      TaskProvider<NoJekyll> noJekyllProvider = project.tasks.register(NO_JEKYLL_TASK_NAME, NoJekyll) { NoJekyll noJekyll ->
        noJekyll.with {
          description = 'Generates .nojekyll file in gitPublish repository'
          destinationDir.set project.extensions.getByType(GitPublishExtension).repoDir
        }
        /*
         * WORKAROUND:
         * Without that we get error:
         * [Static type checking] - Cannot call <T extends org.gradle.api.Task>
         * org.gradle.api.tasks.TaskContainer#register(java.lang.String, java.lang.Class <T>, org.gradle.api.Action
         * <java.lang.Object extends java.lang.Object>) with arguments [java.lang.String, java.lang.Class
         * <org.fidata.gradle.tasks.NoJekyll>, groovy.lang.Closure <java.io.File>]
         * <grv87 2018-07-31>
         */
        null
      }
      /*
       * WORKAROUND:
       * JGit doesn't support signed commits yet.
       * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=382212
       * <grv87 2018-06-22>
       */
      ResignGitCommit.registerTask(project, gitPublishCommitProvider) { ResignGitCommit resignGitPublishCommit ->
        resignGitPublishCommit.with {
          enabled = repoClean
          description = 'Amend git publish commit adding sign to it'
          workingDir.set project.extensions.getByType(GitPublishExtension).repoDir
          onlyIf { gitPublishCommitProvider.get().didWork }
        }
        /*
         * WORKAROUND:
         * Without that we get error:
         * [Static type checking] - Cannot call <T extends org.gradle.api.Task>
         * org.gradle.api.tasks.TaskContainer#register(java.lang.String, java.lang.Class <T>, org.gradle.api.Action
         * <java.lang.Object extends java.lang.Object>) with arguments [groovy.lang.GString, java.lang.Class
         * <org.fidata.gradle.tasks.ResignGitCommit>, groovy.lang.Closure <java.lang.Void>]
         * <grv87 2018-07-31>
         */
        null
      }
      gitPublishCommit.with {
        enabled = repoClean
        dependsOn noJekyllProvider
      }
    }

    TaskProvider<Task> gitPublishPushProvider = project.tasks.named(/* WORKAROUND: GitPublishPlugin.PUSH_TASK has package scope <grv87 2018-06-23> */'gitPublishPush')
    gitPublishPushProvider.configure { Task gitPublishPush ->
      gitPublishPush.enabled = repoClean
    }
    project.tasks.named(RELEASE_TASK_NAME).configure { Task release ->
      release.dependsOn gitPublishPushProvider
    }
  }

  /**
   * Name of lint task
   */
  public static final String LINT_TASK_NAME = 'lint'

  /**
   * Name of CodeNarc common task
   */
  public static final String CODENARC_TASK_NAME = 'codenarc'

  /**
   * Name of disabledRules convention for {@link CodeNarc} tasks
   */
  public static final String CODENARC_DISABLED_RULES_CONVENTION_NAME = 'disabledRules'

  /**
   * Path director for codenarc reports
   */
  static final PathDirector<CodeNarc> CODENARC_REPORT_DIRECTOR = new PathDirector<CodeNarc>() {
    @Override
    @SuppressWarnings('CatchException')
    Path determinePath(CodeNarc object) throws ReportPathDirectorException {
      try {
        Paths.get(toSafeFileName((object.name - ~/^codenarc/ /* WORKAROUND: CodeNarcPlugin.getTaskBaseName has protected scope <grv87 2018-06-23> */).uncapitalize()))
      } catch (Exception e) {
        throw new ReportPathDirectorException(object, e)
      }
    }
  }

  /*
   * WORKAROUND:
   * Groovy bug. Usage of `destination =` instead of setDestination leads to error:
   * [Static type checking] - Cannot set read-only property: destination
   * Also may be CodeNarc error
   * <grv87 2018-06-26>
   */
  @SuppressWarnings('UnnecessarySetter')
  private void configureCodeQuality() {
    TaskProvider<Task> lintProvider = project.tasks.register(LINT_TASK_NAME) { Task lint ->
      lint.with {
        group = VERIFICATION_GROUP
        description = 'Runs all static code analyses'
      }
    }
    TaskProvider<Task> checkProvider = project.tasks.named(CHECK_TASK_NAME)
    checkProvider.configure { Task check ->
      check.dependsOn lintProvider
    }

    TaskCollection<CodeNarc> codenarcTasks = project.tasks.withType(CodeNarc)

    TaskProvider<Task> codenarcProvider = project.tasks.register(CODENARC_TASK_NAME) { Task codenarc ->
      codenarc.with {
        group = 'Verification'
        description = 'Runs CodeNarc analysis for each source set'
        dependsOn codenarcTasks
      }
    }
    lintProvider.configure { Task lint ->
      lint.dependsOn codenarcProvider
    }

    project.extensions.configure(CodeNarcExtension) { CodeNarcExtension extension ->
      extension.reportFormat = 'console'
    }

    checkProvider.configure { Task check ->
      check.taskDependencies.getDependencies(check).removeAll codenarcTasks
    }

    ProjectConvention projectConvention = project.convention.getPlugin(ProjectConvention)
    codenarcTasks.configureEach { CodeNarc codenarc ->
      codenarc.with {
        convention.plugins.put CODENARC_DISABLED_RULES_CONVENTION_NAME, new CodeNarcTaskConvention(codenarc)
        Path reportSubpath = Paths.get('codenarc')
        reports.xml.enabled = true
        reports.xml.setDestination projectConvention.getXmlReportFile(reportSubpath, CODENARC_REPORT_DIRECTOR, codenarc)
        reports.html.enabled = true
        reports.html.setDestination projectConvention.getHtmlReportFile(reportSubpath, CODENARC_REPORT_DIRECTOR, codenarc)
      }
    }

    project.tasks.register("${ /* WORKAROUND: CodeNarcPlugin.getTaskBaseName has protected scope <grv87 2018-06-23> */ 'codenarc' }${ DEFAULT_BUILD_SRC_DIR.capitalize() }", CodeNarc) { CodeNarc codenarc ->
      Closure buildDirMatcher = { FileTreeElement fte ->
        String[] p = fte.relativePath.segments
        int i = 0
        while (i < p.length && p[i] == DEFAULT_BUILD_SRC_DIR) { i++ }
        i < p.length && p[i] == DEFAULT_BUILD_DIR_NAME
      }
      codenarc.with {
        source project.fileTree(dir: project.projectDir, includes: ['*.gradle'])
        source project.fileTree(dir: project.projectDir, includes: ['*.groovy'])
        /*
         * WORKAROUND:
         * We have to pass to CodeNarc resolved fileTree, otherwise we get the following error:
         * Cannot add include/exclude specs to Ant node. Only include/exclude patterns are currently supported.
         * This is not a problem since build sources can't change at build time,
         * and also this code is executed only when codenarcBuildSrc task is actually created (i.e. run)
         * <grv87 2018-08-22>
         */
        source project.fileTree(DEFAULT_BUILD_SRC_DIR) { ConfigurableFileTree fileTree ->
          fileTree.include '**/*.gradle'
          fileTree.include '**/*.groovy'
          fileTree.exclude buildDirMatcher
        }.files
        source project.fileTree(dir: project.file('gradle'), includes: ['**/*.gradle'])
        source project.fileTree(dir: project.file('gradle'), includes: ['**/*.groovy'])
        source project.fileTree(dir: project.file('config'), includes: ['**/*.groovy'])
        source 'Jenkinsfile'
        /*
         * WORKAROUND:
         * Indentation rule doesn't work correctly.
         * https://github.com/CodeNarc/CodeNarc/issues/310
         * <grv87 2018-06-26>
         */
        codenarc.convention.getPlugin(CodeNarcTaskConvention).disabledRules.add 'Indentation'
      }
    }
  }

  /**
   * Name of Diagnostics task group
   */
  public static final String DIAGNOSTICS_TASK_GROUP_NAME = 'Diagnostics'

  /**
   * Name of InputsOutputs task
   */
  public static final String INPUTS_OUTPUTS_TASK_NAME = 'inputsOutputs'

  /*
   * WORKAROUND:
   * Groovy error. Usage of `destination =` instead of setDestination leads to error:
   * [Static type checking] - Cannot set read-only property: destination
   * Also may be CodeNarc error
   * <grv87 2018-06-26>
   */
  @SuppressWarnings('UnnecessarySetter')
  private void configureDiagnostics() {
    ProjectConvention projectConvention = project.convention.getPlugin(ProjectConvention)
    project.convention.getPlugin(ProjectReportsPluginConvention).projectReportDirName = projectConvention.getTxtReportDir(Paths.get('project')).toString()

    project.tasks.withType(BuildEnvironmentReportTask).configureEach { BuildEnvironmentReportTask buildEnvironmentReport ->
      buildEnvironmentReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(ComponentReport).configureEach { ComponentReport componentReport ->
      componentReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(DependencyReportTask).configureEach { DependencyReportTask dependencyReport ->
      dependencyReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(DependencyInsightReportTask).configureEach { DependencyInsightReportTask dependencyInsightReport ->
      dependencyInsightReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(DependentComponentsReport).configureEach { DependentComponentsReport dependentComponentsReport ->
      dependentComponentsReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(ModelReport).configureEach { ModelReport modelReport ->
      modelReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(ProjectReportTask).configureEach { ProjectReportTask projectReport ->
      projectReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(PropertyReportTask).configureEach { PropertyReportTask propertyReport ->
      propertyReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(HtmlDependencyReportTask).configureEach { HtmlDependencyReportTask htmlDependencyReport ->
      htmlDependencyReport.with {
        group = DIAGNOSTICS_TASK_GROUP_NAME
        reports.html.setDestination projectConvention.getHtmlReportDir(Paths.get('dependencies'))
      }
    }
    project.tasks.withType(TaskReportTask).configureEach { TaskReportTask taskReport ->
      taskReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.named(PROJECT_REPORT).configure { Task projectReport ->
      projectReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }

    project.tasks.register(INPUTS_OUTPUTS_TASK_NAME, InputsOutputs) { InputsOutputs inputsOutputs ->
      inputsOutputs.with {
        group = DIAGNOSTICS_TASK_GROUP_NAME
        description = 'Generates report about all task file inputs and outputs'
        outputFile.set new File(projectConvention.txtReportsDir, DEFAULT_OUTPUT_FILE_NAME)
      }
    }

    project.tasks.withType(TaskTreeTask).named(TASK_TREE_TASK_NAME).configure { TaskTreeTask taskTree ->
      taskTree.group = DIAGNOSTICS_TASK_GROUP_NAME
    }

    project.extensions.configure(VisTegPluginExtension) { VisTegPluginExtension extension ->
      extension.with {
        enabled        = (project.logging.level ?: project.gradle.startParameter.logLevel) <= LogLevel.INFO
        colouredNodes  = true
        colouredEdges  = true
        destination    = new File(projectConvention.reportsDir, 'visteg.dot').toString()
        exporter       = 'dot'
        colorscheme    = 'paired12'
        nodeShape      = 'box'
        startNodeShape = 'hexagon'
        endNodeShape   = 'doubleoctagon'
      }
    }
  }
}
