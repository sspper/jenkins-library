#!/usr/bin/groovy

package devops

def runStandardPipeline(podName, buildType, testFolder, additionalRepos) {

  def rasbuild = new devops.rasBuild()
  def rasdelivery = new rasDeliveryv3()
  def image

  try {

    bsettings = rasbuild.settings(podName)
    deployEnv = rasdelivery.branchMap(env.BRANCH_NAME)

    loginToDocker()

    // Build
    docker.image(bsettings.images."${buildType}").inside("-u root") {
      stage ('Build') {
        rasbuild.build(buildType)
      }
    }

    // Clone additional repos in separate stage if they exist
    additionalRepos.each { k, v ->
      stage ("Clone: ${k}") {
        checkoutRepo {
          repo = "${v.repoURL}"
          branch = "${v.branch}"
          folder = "${v.folder}"
        }
      }
    }

    // Test
    docker.image(bsettings.images."${buildType}").inside("-u root") {
      stage ('Test') {
        rasbuild.test(buildType, testFolder)
      }
      if (rasdelivery.runSonarAndWhiteSource() && buildType == "java") {
        stage ('Sonar') { rasbuild.sonar() }
        stage ('Whitesource') { rasbuild.whitesource() }
      }
    }

    docker_tag = rasdelivery.getDockerTag()


    // Deploy Image -- only to mapped environments.  See rasDelivery.branchMap
    if (rasdelivery.deployable(deployEnv)) {
      stage('Package Image') {
        rasdelivery.addVersion(docker_tag, buildType)
        rasdelivery.writeBuildFile("${bsettings.imageName}-${docker_tag}")
        image = docker.build(bsettings.imageName)
      }
      stage ('Publish Image') {
        image.push(docker_tag)
      }
      stage ('Deploy Image') {
        rasdelivery.notifyOfPendingDeployment(podName, deployEnv, bsettings.imageName, docker_tag)
        rasdelivery.deploy(docker_tag, deployEnv, podName)
        rasdelivery.notifyOfCompletedDeployment(podName, deployEnv, bsettings.imageName, docker_tag)
      }
    }

  } catch (e) {
    currentBuild.result = "FAILED"
    rasdelivery.notifyFailure(e.getMessage())
    throw e
  }
}
