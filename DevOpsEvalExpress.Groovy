def COMMIT_SHA
def REGISTRY_URL
def IMAGE_NAME

pipeline {
    agent any

    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git credentialsId: 'DevOps_Repo_SSH', branch: "main", url: 'git@github.com:mustafizEnosis/node-express-hello-devfile-no-dockerfile.git'
            }
        }

        stage('Environment Setup') {
            steps {
                script {
                    REGISTRY_URL = 'localhost:5000'
                    IMAGE_NAME = 'dev-ops-eval'
                }
                nodejs(nodeJSInstallationName: 'NodeJS') {
                    sh 'npm install'
                }
            }
        }

        stage('Package') {
            steps {
                script {
                    echo "Building docker image"
                    COMMIT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    echo "${REGISTRY_URL}"
                    sh "docker build -t ${REGISTRY_URL}/${IMAGE_NAME}:${COMMIT_SHA} ."
                }
            }
        }   

        stage('Deploy') {
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    script {
                        // def tags = sh(script: "curl -s http://host.docker.internal:5000/v2/${IMAGE_NAME}/tags/list", returnStdout: true).trim()
                        // if (tags.contains("\"${COMMIT_SHA}\"")) {
                        //    echo "Image with tag ${COMMIT_SHA} already exists in the local registry"
                        //     currentBuild.result = 'FAILURE'
                        //     sh 'exit 1'
                        // }
                    
                        sh "docker push ${REGISTRY_URL}/${IMAGE_NAME}:${COMMIT_SHA}"
                        
                        sh "docker rmi ${REGISTRY_URL}/${IMAGE_NAME}:${COMMIT_SHA}"
                        
                        sh "docker pull ${REGISTRY_URL}/${IMAGE_NAME}:${COMMIT_SHA}"
                        
                        sh "docker run -d -p 3000:8080 ${REGISTRY_URL}/${IMAGE_NAME}:${COMMIT_SHA}"
                        
                        sleep 5
    
                        def deployed_url = "http://host.docker.internal:3000"
                        def response_code = sh(script: "curl --head --silent --write-out \"%{http_code}\" --output /dev/null \"${deployed_url}\"", returnStdout: true).trim()
                        
                        echo "${response_code}"
                        if (response_code == '200') {
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