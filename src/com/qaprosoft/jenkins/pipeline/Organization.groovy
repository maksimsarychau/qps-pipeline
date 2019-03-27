package com.qaprosoft.jenkins.pipeline

import com.qaprosoft.jenkins.Logger
import com.qaprosoft.jenkins.pipeline.integration.zafira.ZafiraUpdater
import com.qaprosoft.jenkins.pipeline.tools.scm.ISCM
import com.qaprosoft.jenkins.pipeline.tools.scm.github.GitHub
import com.qaprosoft.jenkins.pipeline.Repository
import com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty
import org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy
import jp.ikedam.jenkins.plugins.extensible_choice_parameter.ExtensibleChoiceParameterDefinition
import jenkins.security.ApiTokenProperty

import static com.qaprosoft.jenkins.Utils.*
import static com.qaprosoft.jenkins.pipeline.Executor.*

class Organization {

    def context
    protected Repository repository
    protected ISCM scmClient
    protected Logger logger
    protected ZafiraUpdater zafiraUpdater
    protected def onlyUpdated = false
    protected def currentBuild
    protected def repo
    protected Configuration configuration = new Configuration(context)

    public Organization(context) {
        this.context = context
        repository = new Repository(context)
        scmClient = new GitHub(context)
        logger = new Logger(context)
        zafiraUpdater = new ZafiraUpdater(context)
        repo = Configuration.get("repo")
        onlyUpdated = Configuration.get("onlyUpdated")?.toBoolean()
        currentBuild = context.currentBuild
    }

    def register() {
        logger.info("Organization->register")
        context.node('master') {
            context.timestamps {
                prepare()
                repository.register()
                def jobName = "${repo}" + "/" + "onPush-" + repo
                def job = getJenkinsJobByName(jobName)
                logger.info(job.dump())
                clean()
            }
        }
    }

    protected def createLauncher(repo){
        currentBuild.rawBuild.getAction(javaposse.jobdsl.plugin.actions.GeneratedJobsBuildAction).modifiedObjects.each {
            def currentJobUrl = Configuration.get(Configuration.Parameter.JOB_URL)
            def jobUrl = currentJobUrl.substring(0, currentJobUrl.lastIndexOf("/job/") + "/job/".length()) + it.jobName.substring(it.jobName.lastIndexOf("/"))
            def parameters = getParametersMap(it.jobName)
            zafiraUpdater.createLauncher(parameters, jobUrl, repo)
        }
    }

    private def getParametersMap(jobName) {
        def job = Jenkins.instance.getItemByFullName(jobName)
        def parameterDefinitions = job.getProperty('hudson.model.ParametersDefinitionProperty').parameterDefinitions
        Map parameters = [:]
        parameterDefinitions.each { parameterDefinition ->
            def value
            if(parameterDefinition instanceof ExtensibleChoiceParameterDefinition){
                value = parameterDefinition.choiceListProvider.getDefaultChoice()
            } else if (parameterDefinition instanceof ChoiceParameterDefinition) {
                value = parameterDefinition.choices[0]
            }  else {
                value = parameterDefinition.defaultValue
            }
            parameters.put(parameterDefinition.name, !isParamEmpty(value)?value:'')
        }
        return parameters
    }

    def generateAPIToken(user){
        //saveInZafira(token.tokenName, token.tokenValue)
        return Jenkins.instance.getDescriptorByType(ApiTokenProperty.DescriptorImpl.class).doGenerateNewToken(user, user.getId() + '_token').jsonObject.data
    }

    def createJenkinsUser(userName){
        def password = UUID.randomUUID()
        return Jenkins.instance.securityRealm.createAccount(userName, "123456")
    }

    def grantUserBaseGlobalPermissions(userName){
        def authStrategy = Jenkins.instance.getAuthorizationStrategy()
        authStrategy.add(hudson.model.Hudson.READ, userName)
    }

    def setUserFolderPermissions(folderName, userName) {
        def folder = getJenkinsFolderByName(folderName)
        if(folder == null){
            logger.error("No folder ${folderName} was detected.")
            return
        }
        def authProperty = folder.properties.find {
            it instanceof AuthorizationMatrixProperty
        }
        authProperty.setInheritanceStrategy(new NonInheritingStrategy())
        def permissionsArray = [com.cloudbees.plugins.credentials.CredentialsProvider.CREATE,
                                com.cloudbees.plugins.credentials.CredentialsProvider.DELETE,
                                com.cloudbees.plugins.credentials.CredentialsProvider.MANAGE_DOMAINS,
                                com.cloudbees.plugins.credentials.CredentialsProvider.UPDATE,
                                com.cloudbees.plugins.credentials.CredentialsProvider.VIEW,
                                com.synopsys.arc.jenkins.plugins.ownership.OwnershipPlugin.MANAGE_ITEMS_OWNERSHIP,
                                hudson.model.Item.BUILD,
                                hudson.model.Item.CANCEL,
                                hudson.model.Item.CONFIGURE,
                                hudson.model.Item.CREATE,
                                hudson.model.Item.DELETE,
                                hudson.model.Item.DISCOVER,
                                hudson.model.Item.READ,
                                hudson.model.Item.WORKSPACE,
                                hudson.model.Run.DELETE,
                                hudson.model.Run.UPDATE,
                                org.jenkinsci.plugins.workflow.cps.replay.ReplayAction.REPLAY,
                                hudson.model.View.CONFIGURE,
                                hudson.model.View.CREATE,
                                hudson.model.View.DELETE,
                                hudson.model.View.READ,
                                hudson.scm.SCM.TAG]
        permissionsArray.each {
            authProperty.add(it, userName)
        }
    }

    protected void prepare() {
        scmClient.clone(!onlyUpdated)
        String QPS_PIPELINE_GIT_URL = Configuration.get(Configuration.Parameter.QPS_PIPELINE_GIT_URL)
        String QPS_PIPELINE_GIT_BRANCH = Configuration.get(Configuration.Parameter.QPS_PIPELINE_GIT_BRANCH)
        scmClient.clone(QPS_PIPELINE_GIT_URL, QPS_PIPELINE_GIT_BRANCH, "qps-pipeline")
    }

    protected clean() {
        context.stage('Wipe out Workspace') {
            context.deleteDir()
        }
    }
}