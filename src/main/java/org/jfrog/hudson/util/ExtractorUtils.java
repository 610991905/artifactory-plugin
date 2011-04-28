/*
 * Copyright (C) 2011 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jfrog.hudson.util;

import com.google.common.collect.Lists;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Computer;
import hudson.model.Result;
import hudson.model.Run;
import hudson.slaves.SlaveComputer;
import hudson.tasks.LogRotator;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.api.BuildInfoFields;
import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.client.ArtifactoryClientConfiguration;
import org.jfrog.build.client.ClientProperties;
import org.jfrog.hudson.ArtifactoryServer;
import org.jfrog.hudson.action.ActionableHelper;
import org.jfrog.hudson.release.ReleaseAction;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @author Tomer Cohen
 */
public class ExtractorUtils {
    private ExtractorUtils() {
        // utility class
        throw new IllegalAccessError();
    }

    /**
     * Get the VCS revision from the Jenkins build environment. The search will first be for an "SVN_REVISION" in the
     * environment. If it is not found then a search will be for a "GIT_COMMIT".
     *
     * @param env Th Jenkins build environment.
     * @return The subversion revision if found, or the git revision if found.
     */
    public static String getVcsRevision(Map<String, String> env) {
        String revision = env.get("SVN_REVISION");
        if (StringUtils.isBlank(revision)) {
            revision = env.get("GIT_COMMIT");
        }
        return revision;
    }

    /**
     * Copies a classworlds file to a temporary location either on the local filesystem or on a slave depending on the
     * node type.
     *
     * @return The path of the classworlds.conf file
     */
    public static String copyClassWorldsFile(AbstractBuild<?, ?> build, URL resource, File classWorldsFile) {
        String classworldsConfPath;
        if (Computer.currentComputer() instanceof SlaveComputer) {
            try {
                FilePath remoteClassworlds =
                        build.getWorkspace().createTextTempFile("classworlds", "conf", "", false);
                remoteClassworlds.copyFrom(resource);
                classworldsConfPath = remoteClassworlds.getRemote();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            classworldsConfPath = classWorldsFile.getAbsolutePath();
            File classWorldsConf = new File(resource.getFile());
            try {
                FileUtils.copyFile(classWorldsConf, classWorldsFile);
            } catch (IOException e) {
                build.setResult(Result.FAILURE);
                throw new RuntimeException(
                        "Unable to copy classworlds file: " + classWorldsConf.getAbsolutePath() + " to: " +
                                classWorldsFile.getAbsolutePath(), e);
            }
        }
        return classworldsConfPath;
    }

    /**
     * Add a custom {@code classworlds.conf} file that will be read by the Maven build. Adds an environment variable
     * {@code classwordls.conf} with the location of the classworlds file for Maven.
     *
     * @return The path of the classworlds.conf file
     */
    public static void addCustomClassworlds(Map<String, String> env, String classworldsConfPath) {
        env.put("classworlds.conf", classworldsConfPath);
    }

    /**
     * Add build info properties that will be read by an external extractor. All properties are then saved into a {@code
     * buildinfo.properties} into a temporary location. The location is then put into an environment variable {@link
     * BuildInfoConfigProperties#PROP_PROPS_FILE} for the extractor to read.
     *
     * @param env                       A map of the environment variables that are to be persisted into the
     *                                  buildinfo.properties file
     * @param build                     The build from which to get build/project related information from (e.g build
     *                                  name and build number).
     * @param selectedArtifactoryServer The Artifactory server that is to be used during the build for resolution/
     *                                  deployment
     * @param context                   A container object for build related data.
     */
    public static ArtifactoryClientConfiguration addBuilderInfoArguments(Map<String, String> env, AbstractBuild build,
            ArtifactoryServer selectedArtifactoryServer, BuildContext context)
            throws IOException, InterruptedException {
        ArtifactoryClientConfiguration configuration = new ArtifactoryClientConfiguration(new NullLog());
        configuration.setActivateRecorder(Boolean.TRUE);

        String buildName = build.getProject().getDisplayName();
        configuration.info.setBuildName(buildName);
        configuration.publisher.addMatrixParam("build.name", buildName);
        String buildNumber = build.getNumber() + "";
        configuration.info.setBuildNumber(buildNumber);
        configuration.publisher.addMatrixParam("build.number", buildNumber);

        Date buildStartDate = build.getTimestamp().getTime();
        configuration.info.setBuildStarted(buildStartDate.getTime());
        configuration.info.setBuildTimestamp(String.valueOf(buildStartDate.getTime()));
        configuration.publisher.addMatrixParam("build.timestamp", String.valueOf(buildStartDate.getTime()));

        String vcsRevision = getVcsRevision(env);
        if (StringUtils.isNotBlank(vcsRevision)) {
            configuration.info.setVcsRevision(vcsRevision);
            configuration.publisher.addMatrixParam(BuildInfoFields.VCS_REVISION, vcsRevision);
        }

        if (StringUtils.isNotBlank(context.getArtifactsPattern())) {
            configuration.publisher.setIvyArtifactPattern(context.getArtifactsPattern());
        }
        if (StringUtils.isNotBlank(context.getIvyPattern())) {
            configuration.publisher.setIvyPattern(context.getIvyPattern());
        }
        configuration.publisher.setM2Compatible(context.isMaven2Compatible());
        String buildUrl = ActionableHelper.getBuildUrl(build);
        if (StringUtils.isNotBlank(buildUrl)) {
            configuration.info.setBuildUrl(buildUrl);
        }

        String userName = "unknown";
        Cause.UpstreamCause parent = ActionableHelper.getUpstreamCause(build);
        if (parent != null) {
            String parentProject = parent.getUpstreamProject();
            configuration.info.setParentBuildName(parentProject);
            configuration.publisher.addMatrixParam(BuildInfoFields.BUILD_PARENT_NAME, parentProject);
            String parentBuildNumber = parent.getUpstreamBuild() + "";
            configuration.info.setParentBuildNumber(parentBuildNumber);
            configuration.publisher.addMatrixParam(BuildInfoFields.BUILD_PARENT_NUMBER, parentBuildNumber);
            userName = "auto";
        }

        CauseAction action = ActionableHelper.getLatestAction(build, CauseAction.class);
        if (action != null) {
            for (Cause cause : action.getCauses()) {
                if (cause instanceof Cause.UserCause) {
                    userName = ((Cause.UserCause) cause).getUserName();
                }
            }
        }
        configuration.info.setPrincipal(userName);
        configuration.info.setAgentName("Jenkins");
        configuration.info.setAgentVersion(build.getHudsonVersion());
        configuration.setContextUrl(selectedArtifactoryServer.getUrl());
        configuration.setTimeout(selectedArtifactoryServer.getTimeout());
        configuration.publisher.setRepoKey(context.getDetails().repositoryKey);
        if (StringUtils.isNotBlank(context.getDetails().downloadRepositoryKey)) {
            configuration.resolver.setEnabled(true);
            configuration.resolver.setRepoKey(context.getDetails().downloadRepositoryKey);
        }
        configuration.publisher.setSnapshotRepoKey(context.getDetails().snapshotsRepositoryKey);
        Credentials preferredDeployer =
                CredentialResolver.getPreferredDeployer(context.getDeployerOverrider(), selectedArtifactoryServer);
        if (StringUtils.isNotBlank(preferredDeployer.getUsername())) {
            configuration.publisher.setUserName(preferredDeployer.getUsername());
            configuration.publisher.setPassword(preferredDeployer.getPassword());
        }
        configuration.info.licenseControl.setRunChecks(context.isRunChecks());
        configuration.info.licenseControl.setIncludePublishedArtifacts(context.isIncludePublishArtifacts());
        configuration.info.licenseControl.setAutoDiscover(context.isLicenseAutoDiscovery());
        if (context.isRunChecks()) {
            if (StringUtils.isNotBlank(context.getViolationRecipients())) {
                configuration.info.licenseControl.setViolationRecipients(context.getViolationRecipients());
            }
            if (StringUtils.isNotBlank(context.getScopes())) {
                configuration.info.licenseControl.setScopes(context.getScopes());
            }
        }
        if (context.isDiscardOldBuilds()) {
            LogRotator rotator = build.getProject().getLogRotator();
            if (rotator != null) {
                if (rotator.getNumToKeep() > -1) {
                    configuration.info.setBuildRetentionDays(rotator.getNumToKeep());
                }
                if (rotator.getDaysToKeep() > -1) {
                    configuration.info.setBuildRetentionMinimumDate(String.valueOf(rotator.getDaysToKeep()));
                }
                configuration.info.setDeleteBuildArtifacts(context.isDiscardBuildArtifacts());
            }
            configuration.info.setBuildNumbersNotToDelete(getBuildNumbersNotToBeDeletedAsString(build));
        }
        configuration.publisher.setPublishArtifacts(context.isDeployArtifacts());
        configuration.publisher.setEvenUnstable(context.isEvenIfUnstable());
        configuration.publisher.setIvy(context.isDeployIvy());
        configuration.publisher.setMaven(context.isDeployMaven());
        IncludesExcludes deploymentPatterns = context.getIncludesExcludes();
        if (deploymentPatterns != null) {
            String includePatterns = deploymentPatterns.getIncludePatterns();
            if (StringUtils.isNotBlank(includePatterns)) {
                configuration.publisher.setIncludePatterns(includePatterns);
            }
            String excludePatterns = deploymentPatterns.getExcludePatterns();
            if (StringUtils.isNotBlank(excludePatterns)) {
                configuration.publisher.setExcludePatterns(excludePatterns);
            }
        }
        ReleaseAction releaseAction = ActionableHelper.getLatestAction(build, ReleaseAction.class);
        if (releaseAction != null) {
            configuration.info.setReleaseEnabled(true);
            String comment = releaseAction.getStagingComment();
            if (StringUtils.isNotBlank(comment)) {
                configuration.info.setReleaseComment(comment);
            }
        }
        addBuildRootIfNeeded(build, configuration);
        configuration.publisher.setPublishBuildInfo(!context.isSkipBuildInfoDeploy());
        configuration.setIncludeEnvVars(context.isIncludeEnvVars());
        addMatrixParams(context, configuration.publisher, env);
        addEnvVars(env, build, configuration);
        persistConfiguration(build, configuration, env);
        return configuration;
    }

    /**
     * Get the list of build numbers that are to be kept forever.
     */
    public static List<String> getBuildNumbersNotToBeDeleted(AbstractBuild build) {
        List<String> notToDelete = Lists.newArrayList();
        List<? extends Run<?, ?>> builds = build.getProject().getBuilds();
        for (Run<?, ?> run : builds) {
            if (run.isKeepLog()) {
                notToDelete.add(String.valueOf(run.getNumber()));
            }
        }
        return notToDelete;
    }

    private static String getBuildNumbersNotToBeDeletedAsString(AbstractBuild build) {
        StringBuilder builder = new StringBuilder();
        List<String> notToBeDeleted = getBuildNumbersNotToBeDeleted(build);
        for (String notToDelete : notToBeDeleted) {
            builder.append(notToDelete).append(",");
        }
        return builder.toString();
    }

    public static void addBuildRootIfNeeded(AbstractBuild build, ArtifactoryClientConfiguration configuration) {
        AbstractBuild<?, ?> rootBuild = BuildUniqueIdentifierHelper.getRootBuild(build);
        if (rootBuild != null) {
            String identifier = BuildUniqueIdentifierHelper.getUpstreamIdentifier(rootBuild);
            configuration.info.setBuildRoot(identifier);
        }
    }

    public static void persistConfiguration(AbstractBuild build, ArtifactoryClientConfiguration configuration,
            Map<String, String> env) throws IOException, InterruptedException {
        FilePath tempFile = build.getWorkspace().createTextTempFile("buildInfo", "properties", "", false);
        configuration.setPropertiesFile(tempFile.getRemote());
        env.putAll(configuration.getAllRootConfig());
        configuration.persistToPropertiesFile();
    }

    private static void addMatrixParams(BuildContext context, ArtifactoryClientConfiguration.PublisherHandler publisher,
            Map<String, String> env) {
        String matrixParams = context.getMatrixParams();
        if (StringUtils.isBlank(matrixParams)) {
            return;
        }
        String[] keyValuePairs = StringUtils.split(matrixParams, "; ");
        if (keyValuePairs == null) {
            return;
        }
        for (String keyValuePair : keyValuePairs) {
            String[] split = StringUtils.split(keyValuePair, "=");
            if (split.length == 2) {
                String value = Util.replaceMacro(split[1], env);
                publisher.addMatrixParam(split[0], value);
            }
        }
    }

    private static void addEnvVars(Map<String, String> env, AbstractBuild<?, ?> build,
            ArtifactoryClientConfiguration configuration) {
        // Write all the deploy (matrix params) properties.
        configuration.fillFromProperties(env);
        //Add only the hudson specific environment variables
        MapDifference<String, String> envDifference = Maps.difference(env, System.getenv());
        Map<String, String> filteredEnvDifference = envDifference.entriesOnlyOnLeft();
        configuration.info.addBuildVariables(filteredEnvDifference);

        // add build variables
        Map<String, String> buildVariables = build.getBuildVariables();
        configuration.fillFromProperties(buildVariables);
        for (Map.Entry<String, String> entry : buildVariables.entrySet()) {
            if (entry.getKey().startsWith(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX)) {
                configuration.publisher.addMatrixParam(entry.getKey(), entry.getValue());
            }
        }
        Map<String, String> filteredBuildVars = Maps.newHashMap();

        MapDifference<String, String> buildVarDifference = Maps.difference(buildVariables, filteredBuildVars);
        Map<String, String> filteredBuildVarDifferences = buildVarDifference.entriesOnlyOnLeft();

        configuration.info.addBuildVariables(filteredBuildVarDifferences);
    }
}
