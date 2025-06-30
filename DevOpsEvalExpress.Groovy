def COMMIT_SHA

pipeline {
    agent any
    
    environment {
        REGISTRY_USER = 'mustafizur996'
        IMAGE_NAME = 'dev-ops-eval'
        KUBECONFIG_PATH = '/home/jenkins/.kube/config'
    }
    
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git credentialsId: 'DevOps_Repo_SSH', branch: "k8s-deployment", url: 'git@github.com:mustafizEnosis/node-express-hello-devfile-no-dockerfile.git'
            }
        }

        stage('Package') {
            steps {
                script {
                    echo "Building docker image"
                    COMMIT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    echo "${REGISTRY_USER}"
                    docker.build("${REGISTRY_USER}/${IMAGE_NAME}:${COMMIT_SHA}", ".")
                }
            }
        }
        
        stage('Integrate') {
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    script {
                        docker.withRegistry('https://index.docker.io/v1/', 'DOCKERHUB_REGISTRY_CRED') {
                            // def tags = sh(script: "curl -s https://hub.docker.com/v2/namespaces/${REGISTRY_USER}/repositories/${IMAGE_NAME}/tags/", returnStdout: true).trim()
                            // echo "Available tags in the repo: ${tags}"
                            // if (tags.contains("\"${COMMIT_SHA}\"")) {
                            //     echo "Image with tag ${COMMIT_SHA} already exists in the local registry"
                            //     currentBuild.result = 'FAILURE'
                            //     sh 'exit 1'
                            // }
                            echo "Pushing docker image with tag ${COMMIT_SHA}"
                            def dockerImage = docker.image("${REGISTRY_USER}/${IMAGE_NAME}:${COMMIT_SHA}")
                            dockerImage.push()
                        }
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
                        withEnv(["KUBECONFIG=${env.KUBECONFIG_PATH}"]) {
                            withCredentials([usernamePassword(credentialsId: 'DOCKERHUB_REGISTRY_CRED', usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_PASS')]) {
                                sh 'kubectl create secret docker-registry regcred --docker-server=https://index.docker.io/v1/ --docker-username=${REGISTRY_USER} --docker-password=${REGISTRY_PASS}'
                            }

                            sh "kubectl apply -f k8s-manifests/"
                            sh "kubectl set image deployment/sample-nodejs nodejs-container=${REGISTRY_USER}/${IMAGE_NAME}:${COMMIT_SHA} --record"

                            sh "kubectl rollout status deployment/sample-nodejs --timeout=3m"
                            sleep 3
    
                            def deployed_url = "http://host.docker.internal:30080"
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

        stage('Cleanup') {
            steps {
                script {
                    echo "Cleaning up resources"
                    sh "docker rmi ${REGISTRY_USER}/${IMAGE_NAME}:${COMMIT_SHA}"

                    withEnv(["KUBECONFIG=${env.KUBECONFIG_PATH}"]) {
                            sh "kubectl delete secret regcred"
                    }
                }
            }
        }     
    }
}