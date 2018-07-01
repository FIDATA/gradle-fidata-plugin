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
 *   http://www.apache.org/licenses/LICENSE-2.0
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
import groovy.transform.CompileDynamic
import org.ajoberstar.grgit.Grgit
import de.gliderpilot.gradle.semanticrelease.SemanticReleasePluginExtension
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.fidata.gradle.tasks.CodeNarcTaskConvention
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.internal.plugins.DslObject
import groovy.transform.CompileStatic
import org.fidata.gradle.internal.AbstractPlugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.testing.Test
import org.fidata.gradle.tasks.NoJekyll
import org.fidata.gradle.tasks.ResignGitCommit
import org.gradle.buildinit.tasks.internal.TaskConfiguration
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

/**
 * Provides an environment for a general, language-agnostic project
 */
@CompileStatic
final class ProjectPlugin extends AbstractPlugin {
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
  @Override
  @SuppressWarnings('CouldBeElvis')
  void apply(Project project) {
    assert GradleVersion.current() >= GradleVersion.version(GRADLE_MINIMUM_SUPPORTED_VERSION) : "Gradle versions before $GRADLE_MINIMUM_SUPPORTED_VERSION are not supported"

    super.apply(project)

    PluginDependeesUtils.applyPlugins project, ProjectPluginDependees.PLUGIN_DEPENDEES

    project.convention.plugins.put 'fidata', new ProjectConvention(project)

    if (!project.group) { project.group = 'org.fidata' }

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
    project.tasks.getByName(BUILD_TASK_NAME).dependsOn.remove CHECK_TASK_NAME
    project.tasks.getByName(RELEASE_TASK_NAME).with {
      group = RELEASE_TASK_GROUP_NAME
      dependsOn BUILD_TASK_NAME
      dependsOn project.tasks.getByName(CHECK_TASK_NAME)
    }
    project.tasks.withType(Test) { Test task ->
      task.project.tasks.getByName(CHECK_TASK_NAME).dependsOn task
    }

    project.extensions.getByType(SemanticReleasePluginExtension).branchNames.replace 'develop', ''

    project.tasks.withType(UpdateGithubRelease).getByName('updateGithubRelease').repo.ghToken = project.extensions.extraProperties['ghToken'].toString()
  }

  /**
   * Name of prerequisitesInstall task
   */
  public static final String PREREQUISITES_INSTALL_TASK_NAME = 'prerequisitesInstall'
  /**
   * Name of prerequisitesUpdate task
   */
  public static final String PREREQUISITES_UPDATE_TASK_NAME = 'prerequisitesUpdate'
  /**
   * Name of prerequisitesOutdated task
   */
  public static final String PREREQUISITES_OUTDATED_TASK_NAME = 'prerequisitesOutdated'
  /**
   * Name of resolveAndLockAll task
   */
  public static final String RESOLVE_AND_LOCK_ALL_TASK_NAME = 'resolveAndLockAll'

  @SuppressWarnings(['BracesForForLoop', 'UnnecessaryObjectReferences'])
  private void configurePrerequisitesLifecycle() {
    Task prerequisitesInstall = project.tasks.create(PREREQUISITES_INSTALL_TASK_NAME) { Task task ->
      task.with {
        group = TaskConfiguration.GROUP
        description = 'Install all prerequisites for build'
      }
    }
    Task prerequisitesUpdate = project.tasks.create(PREREQUISITES_UPDATE_TASK_NAME) { Task task ->
      task.with {
        group = TaskConfiguration.GROUP
        description = 'Update all prerequisites that support automatic update'
        mustRunAfter prerequisitesInstall
      }
    }
    Task prerequisitesOutdated = project.tasks.create(PREREQUISITES_OUTDATED_TASK_NAME) { Task task ->
      task.with {
        group = TaskConfiguration.GROUP
        description = 'Show outdated prerequisites'
        mustRunAfter prerequisitesInstall
      }
    }
    project.afterEvaluate {
      for (Task task in
        project.tasks
        - prerequisitesInstall
        - prerequisitesInstall.taskDependencies.getDependencies(prerequisitesInstall)
        - prerequisitesInstall.mustRunAfter.getDependencies(prerequisitesInstall)
        - prerequisitesInstall.shouldRunAfter.getDependencies(prerequisitesInstall)
      ) {
        task.mustRunAfter prerequisitesInstall
      }
      for (Task task in
        project.tasks
        - prerequisitesUpdate
        - prerequisitesUpdate.taskDependencies.getDependencies(prerequisitesUpdate)
        - prerequisitesUpdate.mustRunAfter.getDependencies(prerequisitesUpdate)
        - prerequisitesUpdate.shouldRunAfter.getDependencies(prerequisitesUpdate)
      ) {
        task.mustRunAfter prerequisitesUpdate
      }

      project.dependencyLocking.lockAllConfigurations()

      Task resolveAndLockAll = project.tasks.create(RESOLVE_AND_LOCK_ALL_TASK_NAME) { Task task ->
        task.with {
          doFirst {
            assert project.gradle.startParameter.writeDependencyLocks
          }
          doLast {
            project.configurations.each {
              if (it.canBeResolved) {
                it.resolve()
              }
            }
          }
        }
      }
      prerequisitesUpdate.dependsOn resolveAndLockAll
    }

    project.tasks.withType(DependencyUpdatesTask) { DependencyUpdatesTask task ->
      task.group = null
      task.revision = 'release'
      task.outputFormatter = 'xml'
      task.outputDir = new File(project.convention.getPlugin(ProjectConvention).xmlReportsDir, 'dependencyUpdates').toString()
      task.resolutionStrategy = { ResolutionStrategy resolutionStrategy ->
        resolutionStrategy.componentSelection { ComponentSelectionRules rules ->
          rules.all { ComponentSelection selection ->
            if (task.revision == 'release' && isPreReleaseVersion(selection.candidate.version)) {
              selection.reject 'Pre-release version'
            }
          }
        }
      }
      prerequisitesOutdated.dependsOn task
    }

    project.tasks.withType(Wrapper) { Wrapper task ->
      task.with {
        if (name == 'wrapper') {
          gradleVersion = '4.8.1'
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
        url = project.uri("$ARTIFACTORY_URL/libs-${ project.convention.getPlugin(ProjectConvention).isRelease ? 'release' : 'snapshot' }/")
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
    System.setProperty 'org.ajoberstar.grgit.auth.username', project.extensions.extraProperties['gitUsername'].toString()
    System.setProperty 'org.ajoberstar.grgit.auth.password', project.extensions.extraProperties['gitPassword'].toString()
  }

  /*
   * TODO
   */
  @CompileDynamic
  private boolean isRepoClean() {
    ((Grgit)project.extensions.extraProperties.get('grgit')).status().clean
  }

  /**
   * Name of NoJekyll task
   */
  public static final String NO_JEKYLL_TASK_NAME = 'noJekyll'

  private void configureDocumentation() {
    project.extensions.getByType(GitPublishExtension).with {
      branch.set 'gh-pages'
      preserve.include '**'
      /*
       * CAVEAT:
       * SNAPSHOT documentation for other branches should be removed manually
       */
      preserve.exclude "$project.version/" // TODO - directory ? **
      commitMessage.set COMMIT_MESSAGE_TEMPLATE.make(
        type: 'docs',
        subject: "publish documentation for version ${ project.version }",
        generatedBy: 'org.ajoberstar:gradle-git-publish'
      ).toString()
    }

    boolean repoClean = ((Grgit) project.extensions.extraProperties.get('grgit')).status().clean

    NoJekyll noJekyllTask = project.tasks.create(NO_JEKYLL_TASK_NAME, NoJekyll) { NoJekyll task ->
      task.with {
        description = 'Generates .nojekyll file in gitPublish repository'
        destinationDir = project.extensions.getByType(GitPublishExtension).repoDir.asFile.get()
      }
    }

    project.tasks.getByName(/* WORKAROUND: GitPublishPlugin.COPY_TASK has package scope <grv87 2018-06-23> */ 'gitPublishCopy')

    /*
     * WORKAROUND:
     * JGit doesn't support signed commits yet.
     * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=382212
     * <grv87 2018-06-22>
     */
    ResignGitCommit resignGitCommit = project.tasks.create("${ /* GitPublishPlugin.COMMIT_TASK */ 'gitPublishCommit' }Resign", ResignGitCommit) { ResignGitCommit task ->
      task.with {
        enabled = repoClean
        description = 'Amend git publish commit adding sign to it'
        workingDir = project.extensions.getByType(GitPublishExtension).repoDir.asFile.get()
      }
    }
    project.tasks.getByName(/* WORKAROUND: GitPublishPlugin.COMMIT_TASK has package scope <grv87 2018-06-23> */ 'gitPublishCommit').with {
      enabled = repoClean
      dependsOn noJekyllTask
      finalizedBy resignGitCommit
    }

    project.tasks.getByName(/* WORKAROUND: GitPublishPlugin.PUSH_TASK has package scope <grv87 2018-06-23> */'gitPublishPush').enabled = repoClean
    project.tasks.getByName(RELEASE_TASK_NAME).dependsOn project.tasks.getByName(/* GitPublishPlugin.PUSH_TASK */ 'gitPublishPush')
  }

  /**
   * Name of lint task
   */
  public static final String LINT_TASK_NAME = 'lint'

  /**
   * Name of CodeNarc common task
   */
  public static final String CODENARC_TASK_NAME = 'codenarc'

  /*
   * WORKAROUND:
   * Groovy bug. Usage of `destination =` instead of setDestination leads to error:
   * [Static type checking] - Cannot set read-only property: destination
   * Also may be CodeNarc error
   * <grv87 2018-06-26>
   */
  @SuppressWarnings(['UnnecessarySetter'])
  private void configureCodeQuality() {
    Task lintTask = project.tasks.create(LINT_TASK_NAME) { Task task ->
      task.with {
        group = VERIFICATION_GROUP
        description = 'Runs all static code analyses'
      }
    }
    project.tasks.getByName(CHECK_TASK_NAME).dependsOn lintTask

    Task codeNarcTask = project.tasks.create(CODENARC_TASK_NAME) { Task task ->
      task.with {
        group = 'Verification'
        description = 'Runs CodeNarc analysis for each source set'
      }
    }
    project.tasks.getByName(LINT_TASK_NAME).dependsOn codeNarcTask

    project.extensions.getByType(CodeNarcExtension).with {
      reportFormat = 'console'
    }

    Task checkTask = project.tasks.getByName(CHECK_TASK_NAME)

    project.tasks.withType(CodeNarc) { CodeNarc task ->
      new DslObject(task).convention.plugins.put 'disabledRules', new CodeNarcTaskConvention(task)

      task.with {
        String reportFileName = "codenarc/${ toSafeFileName((name - ~/^codenarc/ /* WORKAROUND: CodeNarcPlugin.getTaskBaseName has protected scope <grv87 2018-06-23> */).uncapitalize()) }"
        reports.xml.enabled = true
        reports.xml.setDestination new File(project.convention.getPlugin(ProjectConvention).xmlReportsDir, "${ reportFileName }.xml")
        reports.html.enabled = true
        reports.html.setDestination new File(project.convention.getPlugin(ProjectConvention).htmlReportsDir, "${ reportFileName }.html")
      }
      codeNarcTask.dependsOn task
      checkTask.taskDependencies.getDependencies(checkTask).remove task
    }

    project.tasks.create("${ /* WORKAROUND: CodeNarcPlugin.getTaskBaseName has protected scope <grv87 2018-06-23> */ 'codenarc' }${ DEFAULT_BUILD_SRC_DIR.capitalize() }", CodeNarc) { CodeNarc task ->
      Closure excludeBuildDir = { FileTreeElement fte ->
        String[] p = fte.relativePath.segments
        int i = 0
        while (i < p.length && p[i] == DEFAULT_BUILD_SRC_DIR) { i++ }
        i < p.length && p[i] == DEFAULT_BUILD_DIR_NAME
      }
      task.with {
        for (File f in project.fileTree(project.projectDir) { ConfigurableFileTree fileTree ->
          fileTree.include '**/*.gradle'
          fileTree.exclude excludeBuildDir
        }) {
          source f
        }
        for (File f in project.fileTree(DEFAULT_BUILD_SRC_DIR) { ConfigurableFileTree fileTree ->
          fileTree.include '**/*.groovy'
          fileTree.exclude excludeBuildDir
        }) {
          source f
        }
        source 'Jenkinsfile'
        source project.fileTree(dir: project.file('config'), includes: ['**/*.groovy'])
      }
      /*
       * WORKAROUND:
       * Indentation rule doesn't work correctly.
       * https://github.com/CodeNarc/CodeNarc/issues/310
       * <grv87 2018-06-26>
       */
      new DslObject(task).convention.getPlugin(CodeNarcTaskConvention).disabledRules.add 'Indentation'
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
  @SuppressWarnings(['UnnecessarySetter'])
  private void configureDiagnostics() {
    project.convention.getPlugin(ProjectReportsPluginConvention).projectReportDirName = project.convention.getPlugin(ProjectConvention).reportsDir.toPath().relativize(new File(project.convention.getPlugin(ProjectConvention).txtReportsDir, 'project').toPath()).toString()

    project.tasks.withType(BuildEnvironmentReportTask) { BuildEnvironmentReportTask task ->
      task.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(ComponentReport) { ComponentReport task ->
      task.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(DependencyReportTask) { DependencyReportTask task ->
      task.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(DependencyInsightReportTask) { DependencyInsightReportTask task ->
      task.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(DependentComponentsReport) { DependentComponentsReport task ->
      task.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(ModelReport) { ModelReport task ->
      task.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(ProjectReportTask) { ProjectReportTask task ->
      task.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(PropertyReportTask) { PropertyReportTask task ->
      task.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(HtmlDependencyReportTask) { HtmlDependencyReportTask task ->
      task.with {
        group = DIAGNOSTICS_TASK_GROUP_NAME
        reports.html.setDestination new File(project.convention.getPlugin(ProjectConvention).htmlReportsDir, 'dependencies')
      }
    }
    project.tasks.withType(TaskReportTask) { TaskReportTask task ->
      task.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.getByName(PROJECT_REPORT).group = DIAGNOSTICS_TASK_GROUP_NAME

    project.tasks.create(INPUTS_OUTPUTS_TASK_NAME, InputsOutputs) { InputsOutputs task ->
      task.with {
        group = DIAGNOSTICS_TASK_GROUP_NAME
        description = 'Generates report about all task file inputs and outputs'
        outputFile = new File(project.convention.getPlugin(ProjectConvention).txtReportsDir, InputsOutputs.DEFAULT_OUTPUT_FILE_NAME)
      }
    }

    project.tasks.withType(TaskTreeTask).getByName(TASK_TREE_TASK_NAME).group = DIAGNOSTICS_TASK_GROUP_NAME

    project.extensions.getByType(VisTegPluginExtension).with {
      enabled        = (project.logging.level ?: project.gradle.startParameter.logLevel) <= LogLevel.INFO
      colouredNodes  = true
      colouredEdges  = true
      destination    = new File(project.convention.getPlugin(ProjectConvention).reportsDir, 'visteg.dot').toString()
      exporter       = 'dot'
      colorscheme    = 'paired12'
      nodeShape      = 'box'
      startNodeShape = 'hexagon'
      endNodeShape   = 'doubleoctagon'
    }
  }
}
