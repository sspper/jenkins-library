#!/usr/bin/groovy
package devops

// Updates driven by provisioning:
// 1. Add ability to have multiple env's in the branch map.
//    ex.) release: int, demo, prov
// 2.
def settings() {
  [
    versionFile: [
      java: 'src/main/resources/app-version.properties',
      node: 'deployment/app-version.properties',
      node9wheezy: 'deployment/app-version.properties'
    ],
    kubectlImage: 'ssartisan/hardroc-kubectl:22'
  ]
}

// Map of environment settings.  Used for deployment
def envMap() {
    [
      'dev' : [context: 'uts-dev', namespace: 'uts-dev', branch: 'develop', cluster: 'dev.uts-squad.com', url: 'dev.uts-squad.com'],
      'qa'  : [context: 'uts-qa', namespace: 'uts-qa', branch: 'develop', cluster: 'qa.uts-squad.com', url: 'qa.uts-squad.com'],
      'int' : [context: 'release.squads-dev.com', namespace: 'int', branch: 'release', cluster: 'release.squads-dev.com', url: 'release.squads-dev.com'],
      'prov' : [context: 'aws-prov', namespace: 'int', branch: 'release', cluster: 'prov.squads-dev.com', url: 'prov.squads-dev.com'],
      'demo': [context: 'aws-root-int', namespace: 'demo', branch: 'release', cluster: 'int.squads-dev.com', url: 'demo.squads-dev.com']
    ]
}

// Map of which branches automatically get deployed. (Continuous delivery/deployment)
def branchMap(branch) {
    def bMap = [
                  'develop' : 'dev',
                  'jenkins' : 'demo',
                  'release' : 'int'
                ]
    return bMap."${branch}"
}

//  Sonar currently only running for Java jobs
def runSonarAndWhiteSource() {
  return env.BRANCH_NAME in ['release', 'master']
}

// Check to see if this pod should be deployed to this environment
def deployable(environment) {
  return (environment && fileExists("deployment/environments/${environment}/env.sh"))
}

// Used as part of standard pipeline.  automatic deployment
def deploy(imageName, environment, pod) {
  def envMap = envMap()

  setupJenkinsRelease(envMap."${environment}".url)
  writeConfigMaps(imageName, environment, pod)
  checkinConfigMaps(imageName)
  deployToK8s(pod, envMap."${environment}".cluster, envMap."${environment}".namespace)
}

// Used by push-button-deploy job.
def pushButtonDeploy(pod, version, environment) {

  def envMap = envMap()
  setupJenkinsRelease(envMap."${environment}".url)

  // checkout repo as basis for config maps only -- pods fetched from dockerhub
  checkoutRepo {
    repo = "https://github.skillsoft.com/goldsmith/${pod}"
    branch = 'develop' // do not match the push-button-deploy branch
    folder = pod
  }
  // Load environment file for pod
  withEnvsFromFile("${pod}/deployment/environments/${environment}/env.sh") {
    // IMG_TAG is the tag name used in Docker hub.  Pulled via rc.yml's
    withEnv(["IMG_TAG=${version}"]) {
      envSubstitute {
        templates_folder = "${pod}/deployment/templates"
        output_folder = "jenkins-release/${pod}"
      }
    }
  }
  checkinConfigMaps(version)
  deployToK8s(pod, envMap."${environment}".cluster, envMap."${environment}".namespace)
}

// Used by push-button-merge job.
def pushButtonMerge(repoName, commit, environment) {
  checkoutRepo {
    repo = "git@github.skillsoft.com:goldsmith/${repoName}.git"
    branch = environment
    folder = repoName
  }
  dir(repoName) {
    sh "git checkout ${environment}; git merge --no-ff ${commit}; git push"
  }
}

// Need to fix checkoutRepo to push correctly
def setupJenkinsRelease2(branchName) {
  checkoutRepo {
    repo = 'https://github.skillsoft.com/DevOps-chapter/jenkins-release'
    branch = branchName
    folder = 'jenkins-release'
  }
}

// Create jenkins-release to push configmaps
def setupJenkinsRelease(branch) {
  if (fileExists('jenkins-release')) {
    sh 'rm -rf jenkins-release'
  }
  echo "Creating jenkins-release repo:"
  setupReleaseRepo()
  sh "cd jenkins-release; git checkout ${branch};"
}

def writeConfigMaps(imageName, environment, pod) {

  // Load environment file for pod
  withEnvsFromFile("deployment/environments/${environment}/env.sh") {
    // IMG_TAG is the tag name used in Docker hub.  Pulled via rc.yml's
    withEnv(["IMG_TAG=${imageName}"]) {
      envSubstitute {
        templates_folder = "deployment/templates"
        output_folder = "jenkins-release/${pod}"
      }
    }
  }
}

def checkinConfigMaps(imageName) {
  // TODO: returns 1 when git push reports no changes, which fails build
  sh "cd jenkins-release; git add .; git commit -m \"Deployment of ${imageName}\" || true; git push || true"
}

def deployToK8s(folder, context, namespace) {
  def settings = settings()
  docker.image(settings.kubectlImage).inside("-u root") {
    def cmd = "kubectl apply -f ${folder} --context=${context} --namespace=${namespace} 2>&1"
    output = runCMD("kubectl apply -f jenkins-release/${folder} --context=${context} --namespace=${namespace} 2>&1")
    echo "OUTPUT: ${output}"
  }
}

def deploySubSystem(environment, repoTags) {
  repoTags.each { repoVersion ->
    repoVersion.each {
      pod, version -> pushButtonDeploy(pod, version, env.BRANCH_NAME)
    }
  }
}

def merge(environment, repoTags) {
  repoTags.each { repoVersion ->
    repoVersion.each {
      repo, commit -> pushButtonMerge(repo, commit, env.BRANCH_NAME)
    }
  }
}


def runCMD(cmd) {
  echo cmd
  return sh(returnStdout: true, script: cmd).trim()
}

def addVersion(docker_tag, buildType) {
  def commit_hash = gitCommit()
  def versionFile = settings().versionFile."${buildType}"
  sh "echo date=`date +%Y-%m-%d` > ${versionFile}"
  sh "echo build=${docker_tag} >> ${versionFile}"
  sh "echo version=${docker_tag} >> ${versionFile}"
  sh "echo commit=${commit_hash} >> ${versionFile}"
}

def writeBuildFile(docker_container = null) {
  def jsonOut = readJSON text: '{}'
  jsonOut.commit = gitCommit()
  jsonOut.branch = env.BRANCH_NAME
  jsonOut.buildDate = new Date().format("yyyyMMdd'T'HH:mm:ss.'S'Z", TimeZone.getTimeZone('UTC'))
  jsonOut.image = docker_container.toString()
  jsonOut.buildnumber = env.BUILD_NUMBER

  writeJSON file: 'build.json', json: jsonOut
}

def gitCommit() {
  runCMD( "git rev-parse --short HEAD" )
}

def getDockerTag() {
  def commit_hash = gitCommit()
  return "${env.BRANCH_NAME}${env.BUILD_NUMBER}-${commit_hash}"
}

def notify(color, message) {
  slackSend(
    color: color,
    channel: 'nashua-notify',
    message: message
  )
}

def notifyFailure(message) {
  def wrappedMessage = "(<${env.BUILD_URL}|Job>) `${JOB_NAME}:${env.BUILD_NUMBER}`\nBuild failed!  Error: ${message}"
  slackSend(
    color: 'danger',
    channel: 'nashua-notify',
    message: wrappedMessage
  )
}

def notifyOfPendingDeployment(projectName, environmentName, imageName, imageTag) {
  notify('warning', "(<${env.BUILD_URL}|Job>) `${JOB_NAME}:${env.BUILD_NUMBER}`\nDeploying ${imageName}:${imageTag} to ${environmentName}")
}

def notifyOfCompletedDeployment(projectName, environmentName, imageName, imageTag) {
  notify('good', "(<${env.BUILD_URL}|Job>) `${JOB_NAME}:${env.BUILD_NUMBER}`\nDeployed ${imageName}:${imageTag} to ${environmentName}")
}
