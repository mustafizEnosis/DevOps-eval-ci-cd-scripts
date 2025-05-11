def COMMIT_SHA

pipeline {
    agent any
    
    environment {
        REGISTRY_URL = 'localhost:5000'
        IMAGE_NAME = 'dev-ops-eval'
    }
    
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git credentialsId: 'DevOps_Repo_SSH', branch: "main", url: 'git@github.com:mustafizEnosis/node-express-hello-devfile-no-dockerfile.git'
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
        
        stage('Integrate') {
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    script {
                        def tags = sh(script: "curl -s http://host.docker.internal:5000/v2/${IMAGE_NAME}/tags/list", returnStdout: true).trim()
                        if (tags.contains("\"${COMMIT_SHA}\"")) {
                            echo "Image with tag ${COMMIT_SHA} already exists in the local registry"
                            currentBuild.result = 'FAILURE'
                            sh 'exit 1'
                        }

                        echo "Pushing docker image to ${REGISTRY_URL}"
                        sh "docker push ${REGISTRY_URL}/${IMAGE_NAME}:${COMMIT_SHA}"
                        
                        sh "docker rmi ${REGISTRY_URL}/${IMAGE_NAME}:${COMMIT_SHA}"
                    }
                }
            }
        }

        stage('Deploy') {
            when {
                expression {currentBuild.result != 'FAILURE'}
            }
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    script {
                        echo "Pulling docker image from ${REGISTRY_URL}"
                        sh "docker pull ${REGISTRY_URL}/${IMAGE_NAME}:${COMMIT_SHA}"
                        
                        sh "docker run -d -p 3000:8080 ${REGISTRY_URL}/${IMAGE_NAME}:${COMMIT_SHA}"
                        
                        sleep 3
    
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