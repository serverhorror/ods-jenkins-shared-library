package org.ods.quickstarter

class Pipeline implements Serializable {

  private def script
  private Map config

  Pipeline(def script, Map config) {
    this.script = script
    this.config = config
  }

  def execute(Closure block) {
    // build params
    checkRequiredBuildParams()
    config.odsImageTag = script.env.ODS_IMAGE_TAG ?: 'latest'
    config.odsGitRef = script.env.ODS_GIT_REF ?: 'production'
    config.projectId = script.env.PROJECT_ID
    config.componentId = script.env.COMPONENT_ID.toLowerCase()
    config.gitUrlHttp = script.env.GIT_URL_HTTP
    config.packageName = script.env.PACKAGE_NAME
    config.group = script.env.GROUP_ID

    // config options
    if (!config.quickstarterId) {
      script.error "Config option 'quickstarterId' is required but not given!"
    }
    if (!config.image && !config.podContainers) {
      script.error "Config option 'image' or 'podContainers' is required but not given!"
    }
    if (!config.cdUserCredentialsId) {
      config.cdUserCredentialsId = "${config.projectId}-cd-cd-user-with-password"
    }
    if (!config.outputDir) {
      config.outputDir = 'out'
    }
    if (!config.podVolumes) {
      config.podVolumes = []
    }

    // vars from jenkins master
    def gitHost
    script.node {
      gitHost =  script.env.BITBUCKET_HOST.split(":")[0]
      config.jobName = script.env.JOB_NAME
      config.buildNumber = script.env.BUILD_NUMBER
      config.buildUrl = script.env.BUILD_URL
      config.buildTime = new Date()
    }

    onAgentNode(config) { context ->
      new CheckoutStage(script, context).execute()
      new CreateOutputDirectoryStage(script, context).execute()

      // Execute user-defined stages.
      block(context)

      new PushToRemoteStage(script, context, [gitHost: gitHost]).execute()
    }
  }

  private def checkRequiredBuildParams() {
    def requiredParams = ['PROJECT_ID', 'COMPONENT_ID', 'GIT_URL_HTTP']
    for (def i = 0; i < requiredParams.size(); i++) {
      def param = requiredParams[i]
      if (!script.env[param]) {
        script.error "Build param '${param}' is required but not given!"
      }
    }
  }

  private def onAgentNode(Map config, Closure block) {
    if (!config.podContainers) {
      if (!config.containsKey('alwaysPullImage')) {
        config.alwaysPullImage = true
      }
      if (!config.podServiceAccount) {
        config.podServiceAccount = 'jenkins'
      }
      if (!config.resourceRequestMemory) {
        config.resourceRequestMemory = '512Mi'
      }
      if (!config.resourceLimitMemory) {
        config.resourceLimitMemory = '1Gi'
      }
      if (!config.resourceRequestCpu) {
        config.resourceRequestCpu = '100m'
      }
      if (!config.resourceLimitCpu) {
        config.resourceLimitCpu = '1'
      }
      config.podContainers = [
        script.containerTemplate(
          name: 'jnlp',
          image: config.image,
          workingDir: '/tmp',
          alwaysPullImage: config.alwaysPullImage,
          resourceRequestMemory: config.resourceRequestMemory,
          resourceLimitMemory: config.resourceLimitMemory,
          resourceRequestCpu: config.resourceRequestCpu,
          resourceLimitCpu: config.resourceLimitCpu,
          args: ''
        )
      ]
    }

    def podLabel = "quickstarter-${config.quickstarterId}-${config.projectId}-${config.componentId}"

    script.podTemplate(
      label: podLabel,
      cloud: 'openshift',
      containers: config.podContainers,
      volumes: config.podVolumes,
      serviceAccount: config.podServiceAccount
    ) {
      script.node(podLabel) {
        IContext context = new Context(config)
        block(context)
      }
    }
  }
}
