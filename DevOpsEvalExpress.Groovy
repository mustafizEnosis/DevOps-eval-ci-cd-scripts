def COMMIT_SHA
def REGISTRY_URL
def IMAGE_NAME

pipeline {
    agent any

    environment {
        REGISTRY_URL = 'local-registry'
        IMAGE_NAME = 'DevOpsEval'
    }

    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git credentialsId: 'DevOps_Repo_SSH', branch: "main", url: 'git@github.com:mustafizEnosis/node-express-hello-devfile-no-dockerfile.git'
            }
        }

        stage('Environment Setup') {
            steps {
                nodeJs(nodeJSInstallationName: 'NodeJS') {
                    sh 'npm install'
                }
            }
        }

        stage('Package') {
            steps {
                script {
                    echo "Building docker image"
                    COMMIT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    docker.build("${REGISTRY_URL}/${IMAGE_NAME}:${COMMIT_SHA}")
                }
            }
        }   

        stage('Deploy') {
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    script {
                        def tags = sh(script: "curl -s http://${REGISTRY_URL}/v2/${IMAGE_NAME}/tags/list", returnStdout: true).trim()
                        if (tags.contains("\"${COMMIT_SHA}\"")) {
                            echo "Image with tag ${COMMIT_SHA} already exists in the local registry"
                            currentBuild.result = 'FAILURE'
                            sh 'exit 1'
                        }

                        docker.withRegistry("http://${REGISTRY_URL}") {
                            docker.image("${REGISTRY_URL}/${IMAGE_NAME}:${COMMIT_SHA}").push()
                        }

                        sh "docker rmi ${REGISTRY_URL}/${IMAGE_NAME}:${COMMIT_SHA}"

                        docker.withRegistry("http://${REGISTRY_URL}") {
                            def image = docker.image("${REGISTRY_URL}/${IMAGE_NAME}:${COMMIT_SHA}")
                            image.pull()

                            image.run('-d -p 3000:8080')

                            sleep 5
                        }

                        def deployed_url = "http://localhost:3000"
                        def response_code = sh(script: "curl --head --silent --write-out \"%{http_code}\" --output /dev/null \"${deployed_url}\")", returnStdout: true).trim()

                        if (response_code == 200) {
                            echo "Deployment successfull!"
                        }
                        else {
                            echo "Deployment failed"
                            currentBuild.result = 'FAILURE'
                            sh 'exit 1'
                        }
                    }
                }
            }
        }     
    }
}