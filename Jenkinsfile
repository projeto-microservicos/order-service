pipeline {
  agent { label 'eks-deploy' }
  options {
    timestamps()
    disableConcurrentBuilds()
    skipDefaultCheckout(true)
    timeout(time: 45, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }
  parameters {
    string(name: 'AWS_REGION', defaultValue: '', description: 'Region of ECR and EKS, e.g. us-east-1')
    string(name: 'AWS_ACCOUNT_ID', defaultValue: '', description: 'Expected AWS account (12 digits)')
    string(name: 'ECR_REPOSITORY', defaultValue: 'order-service', description: 'Existing private ECR repository')
    string(name: 'EKS_CLUSTER_NAME', defaultValue: '', description: 'Existing EKS cluster name')
    string(name: 'K8S_NAMESPACE', defaultValue: 'store', description: 'Existing application namespace')
    choice(name: 'IMAGE_PLATFORM', choices: ['linux/amd64', 'linux/arm64'], description: 'Architecture of the target EKS nodes')
    string(name: 'AWS_CREDENTIALS_ID', defaultValue: '', description: 'Optional Jenkins AWS credential ID; empty uses the agent IAM role')
    booleanParam(name: 'DEPLOY', defaultValue: false, description: 'Apply this service and wait for rollout; false only publishes the image')
  }
  environment { SERVICE = 'order'; AWS_PAGER = '' }
  stages {
    stage('Checkout') {
      steps { deleteDir(); checkout scm }
    }
    stage('Tests') {
      steps {
        sh 'python3 -m unittest discover -s tests_ci -v'
        sh 'mvn -B -ntp clean verify'
      }
      post { always { junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml' } }
    }
    stage('Vulnerability scan') {
      steps { sh 'bash scripts/scan.sh' }
    }
    stage('Publish to ECR and deploy to EKS') {
      steps {
        script {
          if (params.AWS_CREDENTIALS_ID?.trim()) {
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: params.AWS_CREDENTIALS_ID.trim()]]) {
              sh 'bash scripts/release-eks.sh'
            }
          } else {
            sh 'bash scripts/release-eks.sh'
          }
        }
      }
    }
  }
  post {
    always { archiveArtifacts artifacts: 'artifacts/*', allowEmptyArchive: true }
  }
}
