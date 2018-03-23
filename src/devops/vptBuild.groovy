#!/usr/bin/env groovy
package devops

def settings(podName) {
  [
    images: [
      java: 'ssartisan/goldsmith-build-tools:ras-java-build-1',
      // node: 'docker.io/node:8.4-alpine'
      node: 'node:8-slim',
      node8wheezy: 'node:wheezy',
      node6wheezy: 'node:6-wheezy',
      node9wheezy: 'node:9-wheezy',
      node8: 'node:8'
    ],
    imageName : "ssartisan/goldsmith-${podName}"
  ]
}

def build(buildType) {
  checkout scm
  switch(buildType) {
    case 'java':
      def commitHash = checkout(scm).GIT_COMMIT.take(7)
      sh "echo ${commitHash} > src/main/resources/commit-hash"
      javabuild()
      break
    case 'node':
      nodebuild()
      break
    default:
      return 1
  }
}

def test(buildType, testFolder) {
  checkout scm
  switch(buildType) {
    case 'java':
      javaTest(testFolder)
      break
    case 'node':
      nodeTest(testFolder)
      break
    default:
      return 1
  }
}

def javabuild() {
  sh './gradlew --gradle-user-home .gradle/ --no-daemon build shadowJar -x test'
}

def nodebuild() {
  sh 'npm install ava --quiet --registry https://nexus.rocs.io:8081/repository/npm-public/'
  sh 'npm install --quiet --registry https://nexus.rocs.io:8081/repository/npm-public/'
}

def javaTest(resultDir) {
  sh 'docker-entrypoint.sh postgres &'
  try {
    sh './gradlew --gradle-user-home .gradle/ --no-daemon test || true'
  } finally {
    // https://stackoverflow.com/a/14702928 - to avoid stale test runs.
    sh "cd ${resultDir} && touch *.xml"
    junit testResults: "**/${resultDir}/TEST-*.xml"
  }
}

def nodeTest(resultDir) {
  if (!resultDir) { return } // Handles repos with no tests
  try {
    sh 'npm test || true'
  } finally {
    // https://stackoverflow.com/a/14702928 - to avoid stale test runs.
    sh "cd ${resultDir} && touch *.xml"
    junit testResults: "**/${resultDir}/TEST-*.xml"
  }
}

def sonar() {
  withSonarQubeEnv('Sonar') {
    // requires SonarQube Scanner for Gradle 2.1+
    // It's important to add --info because of SONARJNKNS-281
    // echo sh(script: 'env|sort', returnStdout: true)
    sh './gradlew --gradle-user-home .gradle/ --no-daemon --info sonarqube || true'
  }
}

def whitesource() {
  sh './gradlew --gradle-user-home .gradle/ --no-daemon updatewhitesource'
}

def checkoutUtsContainerRepo() {
  if (fileExists('uts-container-api')) {
    echo "Found reference uts-container-api repo"
  } else {
    echo "Creating uts-container-api repo:"
    sh 'git clone git@github.skillsoft.com:goldsmith/uts-container-api.git'
  }
}
