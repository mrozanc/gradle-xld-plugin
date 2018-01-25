/**
 * Copyright 2018 XEBIALABS
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.xebialabs.gradle.xldeploy;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.xebialabs.deployit.booter.remote.DeployitCommunicator;
import com.xebialabs.deployit.booter.remote.Proxies;
import com.xebialabs.deployit.booter.remote.client.DeployitRemoteClient;
import com.xebialabs.deployit.engine.api.RepositoryService;
import com.xebialabs.deployit.engine.api.TaskService;
import com.xebialabs.deployit.engine.api.dto.Deployment;
import com.xebialabs.deployit.engine.api.dto.ValidatedConfigurationItem;
import com.xebialabs.deployit.engine.api.execution.StepState;
import com.xebialabs.deployit.engine.api.execution.TaskExecutionState;
import com.xebialabs.deployit.engine.api.execution.TaskState;
import com.xebialabs.deployit.plugin.api.reflect.Type;
import com.xebialabs.deployit.plugin.api.udm.ConfigurationItem;
import com.xebialabs.deployit.plugin.api.udm.base.BaseConfigurationItem;
import com.xebialabs.deployit.plugin.api.validation.ValidationMessage;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.logging.Logger;
import org.joda.time.DateTime;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.List;

import static com.google.common.collect.Lists.newArrayList;
import static java.lang.String.format;

public class DeploymentHelper {

    private Logger log;

    private Proxies proxies;

    private DeployitCommunicator communicator;

    public static final int TASK_WAIT_TIMEOUT_MS = 1000;

    public DeploymentHelper(Logger logger, DeployitCommunicator communicator) {
        this.log = logger;
        this.proxies = communicator.getProxies();
        this.communicator = communicator;
    }


    /**
     * Checks whether application from given source is deployed to given target
     */
    public boolean isApplicationDeployed(String source, String target) {

        List<String> splitSource = Lists.newArrayList(Splitter.on("/").split(source));
        final String appName = splitSource.get(splitSource.size() - 2);
        String deployedPath = Joiner.on("/").join(target, appName);

        return proxies.getRepositoryService().exists(deployedPath);
    }

    /**
     * Skips all steps of the given task
     */
    public void skipAllSteps(String taskId) {
        TaskService taskService = proxies.getTaskService();
        taskService.skip(taskId, Lists.newArrayList(ContiguousSet.create(
                Range.open(0, taskService.getTask(taskId).getNrSteps() + 1), DiscreteDomain.integers())));
    }

    /**
     * Starts task, waits until it's done.
     * Returns one of the states: {DONE, EXECUTED, STOPPED, CANCELLED}.
     */
    public TaskExecutionState executeAndArchiveTask(String taskId) {
        TaskService taskService = proxies.getTaskService();

        log.info("-----------------------");
        log.info("Task execution plan: ");
        log.info("-----------------------");
        logTaskState(taskId);

        log.info("-----------------------");
        log.info("Task execution progress: ");
        log.info("-----------------------");
        taskService.start(taskId);

        TaskState taskState = taskService.getTask(taskId);
        int lastLoggedStep = -1;

        while (!taskState.getState().isPassiveAfterExecuting()) {
            try {
                Thread.sleep(TASK_WAIT_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            log.debug("Waiting for task to be done...");

            taskState = taskService.getTask(taskId);

            if (taskState.getCurrentStepNr() > lastLoggedStep) {
                logStepState(taskId, taskState.getCurrentStepNr());
                lastLoggedStep = taskState.getCurrentStepNr();
            }
        }

        log.info("-----------------------");
        log.info("Task execution result: ");
        log.info("-----------------------");
        logTaskState(taskId);

        taskService.archive(taskId);

        return taskState.getState();
    }


    /**
     * Logs information about task and all steps
     */
    public void logTaskState(String taskId) {
        TaskState taskState = proxies.getTaskService().getTask(taskId);
        log.info(format("%s Description    %s", taskId, taskState.getDescription()));
        log.info(format("%s State          %s %d/%d", taskId, taskState.getState(), taskState.getCurrentStepNr(), taskState.getNrSteps()));

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss");
        logDate("%s Start      %s", taskId, taskState.getStartDate(), sdf);
        logDate("%s Completion %s", taskId, taskState.getCompletionDate(), sdf);

        for (int i = 1; i <= taskState.getNrSteps(); i++) {
            logStepState(taskId, i);
        }

        if (TaskExecutionState.STOPPED.equals(taskState.getState()))
            throw new IllegalStateException(format("Errors when executing task %s", taskId));
    }

    private void logDate(String pattern, String taskId, DateTime date, SimpleDateFormat sdf) {
        if (date != null) {
            final GregorianCalendar startDate = date.toGregorianCalendar();
            log.info(format(pattern, taskId, sdf.format(startDate.getTime())));
        }
    }

    /**
     * Logs information about single step
     */
    public void logStepState(String taskId, int stepNumber) {
        final StepState stepInfo = proxies.getTaskService().getStep(taskId, stepNumber, null);
        final String description = stepInfo.getDescription();
        String stepInfoMessage;
        if (StringUtils.isEmpty(stepInfo.getLog()) || description.equals(stepInfo.getLog())) {
            stepInfoMessage = format("%s step #%d %s\t%s", taskId, stepNumber, stepInfo.getState(), description);
        } else {
            stepInfoMessage = format("%s step #%d %s\t%s\n%s", taskId, stepNumber, stepInfo.getState(), description, stepInfo.getLog());
        }

        log.info(stepInfoMessage);

    }

    /**
     * Checks if deployment valid (does not contain validation messages)
     */
    public Deployment validateDeployment(Deployment deployment) throws DeploymentValidationError {

        List<ValidationMessage> validationMessages = newArrayList();

        Deployment validatedDeployment = proxies.getDeploymentService().validate(deployment);

        for (ConfigurationItem configurationItem : validatedDeployment.getDeployeds()) {
            if (!(configurationItem instanceof ValidatedConfigurationItem)) {
                continue;
            }
            for (ValidationMessage msg : ((ValidatedConfigurationItem) configurationItem).getValidations()) {
                validationMessages.add(msg);
            }
        }

        if (!validationMessages.isEmpty()) {
            throw new DeploymentValidationError(validationMessages);
        }

        return validatedDeployment;
    }

    public static class DeploymentValidationError extends Exception {

        private List<ValidationMessage> validationMessages = newArrayList();

        public DeploymentValidationError(List<ValidationMessage> validationMessages) {
            super("Deployment contains " + validationMessages.size() + " validation messages, see above");
            this.validationMessages = validationMessages;
        }

        public List<ValidationMessage> getValidationMessages() {
            return validationMessages;
        }
    }

    public static class EnvironmentAlreadyExistsError extends Exception {

        public EnvironmentAlreadyExistsError(String envId) {
            super(format("Can not create environment [%s] because it already exists.", envId));
        }

    }

    /**
     * Returns configuration item from repository, or null if it does not exist
     */
    public ConfigurationItem readCiOrNull(String environmentId) {

        log.debug("reading the environment " + environmentId);

        RepositoryService repositoryService = proxies.getRepositoryService();
        if (repositoryService.exists(environmentId)) {
            return repositoryService.read(environmentId);
        }

        return null;
    }

    /**
     * logs environment and it's members
     */
    public void logEnvironment(ConfigurationItem envCi) {
        if (envCi != null) {
            log.debug(" dumping members of " + envCi.getId());
            List<String> members = envCi.getProperty("members");
            for (String container : members) {
                log.debug(
                        format(" Member: %s ", container)
                );
            }
        }
    }

    /**
     * Creates an environment with members
     */
    public ConfigurationItem createEnvironment(String id, List<? extends ConfigurationItem> members) throws EnvironmentAlreadyExistsError {

        RepositoryService repositoryService = proxies.getRepositoryService();

        if (repositoryService.exists(id))
            throw new EnvironmentAlreadyExistsError(id);

        for (ConfigurationItem member : members) {
            repositoryService.create(member.getId(), member);
        }

        BaseConfigurationItem env = new BaseConfigurationItem();
        env.setType(Type.valueOf("udm.Environment"));
        env.setId(id);

        List<Object> memberIds = Lists.transform(members, new Function<ConfigurationItem, Object>() {
            @Override
            public Object apply(ConfigurationItem input) {
                return input.getId();
            }
        });

        env.setProperty("members", memberIds);

        return repositoryService.create(id, env);
    }

    /**
     * Uploads and imports a .dar package to deployit.
     */
    public ConfigurationItem uploadPackage(File darFile) {
        log.lifecycle("Importing dar file " + darFile.getAbsolutePath());
        return new DeployitRemoteClient(communicator).importPackage(darFile.getAbsolutePath());
    }

}
