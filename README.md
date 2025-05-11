## Prerequisites:
- Install Docker Engine on the host machine. Please follow this [document](https://docs.docker.com/engine/install/ubuntu/#install-using-the-repository) to install Docker on the Linux host machine.

## Describing docker-compose.yml:
- Go to the docker compose file directory.
- Run the docker compose file to run the jenkins and registry containers: `docker compose up`.
- There are two services (Jenkins and registry) in the compose file.
- Jenkins container needs root permissions to run Docker CLI commands. That’s why the user and privileged keys are used.
- To share the host machine's Docker, /var/run/docker.sock and /usr/bin//docker files are mounted as volumes.
- To persist the jenkins container data, jenkins_home volume is created.
- In order to access the host machine’s IP address, the extra_hosts key is used, and the host_gateway is mapped to host.docker.internal.
- Two ports are mapped with the Jenkins container: 8080 and 50000
- A local registry setup using the public [registry Docker image](https://hub.docker.com/_/registry).
- To persist the registry container data, a registry_data volume is created.
- restart:always key is used to automatically restart the containers if they exit for any reason (except when they are explicitly stopped).

## Setup Jenkins:
- As the Jenkins container is running, go to `http://localhost:8080` and provide the admin (username) password found with the below command.
  - sudo docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
- Install the suggested plugins. Additional plugins can be installed from Manage Jenkins -> Manage plugins
- Add GitHub SSH credential: An SSH credential for GitHub is created using this [document](https://docs.github.com/en/authentication/connecting-to-github-with-ssh/generating-a-new-ssh-key-and-adding-it-to-the-ssh-agent?platform=linux). Then in Jenkins, go to Manage Jenkins -> Credentials -> (System) Global Domains -> Select SSH Username with private key option and fill data in the fields. Ensure the ID of the credential is DevOps_Repo_SSH
- Create and configure job
  - Click Dashboard -> New Item
  - Select Pipeline by providing a name.
  - Go to Configure -> Pipeline editor, paste the Jenkinsfile or the Groovy script.
- Click the Build Now button

## Docker build process:
- Run the below command to build the application docker image ([Dockerfile](https://github.com/mustafizEnosis/node-express-hello-devfile-no-dockerfile/blob/main/Dockerfile) is found in the root directory of the project)
    - `docker build -t <REGISTRY_URL>/<IMAGE_NAME>:<tag> .`
    - Here, REGISTRY_URL is the local registry URL.
    - So, in this case, the command will be:
      - `docker build -t localhost:5000/<IMAGE_NAME>:<tag> .`
    - Breakdown of each step in the build process/Dockerfile.
      - Docker packages up all the files and directories and sends them to the Docker daemon.
      - The base image `node:23-alpine` is downloaded and is the starting point/first layer for the final image.
      - Then the package.json and package-lock.json files are copied to the /usr/src/app directory of the image.
      - A new layer is added, and the npm install command is executed to install the application packages.
      - The project folder is copied into the /usr/src/app directory of the image.
      - Http Port 8080 is exposed.
      - Now, the entry point of the image is defined. The default command is npm start which will be executed when a container is started from this image.
- Run the below command to push the Docker image to the registry running at port 5000.
  - `docker push <REGISTRY_URL>/<IMAGE_NAME>:<tag>`
  - So, in this case, the command will be: `docker push localhost:5000/<IMAGE_NAME>:<tag>`

## Jenkinsfile explanation: 
- Two environment variables are used.
  1. REGISTRY_URL: Set to `localhost:5000`
  2. IMAGE_NAME: Set to `dev-ops-eval`
- There are four stages in the Jenkinsfile.
  1. Checkout: Clone the repo and checkout to the main branch using the DevOps_Repo_SSH credential.
  2. Package:
    - Take the latest commit sha to use as a tag of the docker image.
      - `git rev-parse --short HEAD`
    - Build the Docker image and use the local registry URL in the tag so that it can be pushed there.
      - `docker build -t localhost:5000/<IMAGE_NAME>:<COMMIT_SHA> .`
  3. Integrate:
    - Push the Docker image to the local registry running at the host machine’s port 5000.
      - `docker push localhost:5000/<IMAGE_NAME>:<COMMIT_SHA>`
    - Remove the local Docker built image using the below command.
      - `docker rmi localhost:5000/<IMAGE_NAME>:<COMMIT_SHA>`
  4. Deploy:
    - Pull the image from the local registry.
      - `docker pull localhost:5000/${IMAGE_NAME}:${COMMIT_SHA}`
    - Get the container ID of the currently deployed one and stop that.
      - `def container_id = sh(script: "docker ps --filter \"publish=3000\" --format \"{{.ID}}\"", returnStdout: true).trim()`
      - `docker stop ${container_id}`    
    - Run the pulled Docker image using the following command to ensure it is running in detached mode and maps the 8080 port to host machine's 3000 port.
      - `docker run -d -p 3000:8080 localhost:5000/${IMAGE_NAME}:${COMMIT_SHA}`
    - Sleep a little bit to ensure the express server is running within the docker container.
    - Verify whether the server is running by invoking the below curl command and checking whether the response code is 200.
      - `curl --head --silent --write-out \"%{http_code}\" --output /dev/null \"http:\\host.docker.internal:3000\"`

## Issues faced and resolutions
1. permission denied while trying to connect to the Docker daemon socket
  - Add your user to the docker group and restart the machine
    - `sudo usermod -aG docker $USER`
2. You're using 'Known hosts file' strategy to verify ssh host keys, but your known_hosts file does not exist, please go to 'Manage Jenkins' -> 'Security' -> 'Git Host Key Verification Configuration' and configure host key verification.
  - Go to Manage Jenkins -> Security. Scroll down to Git Host Key Verification Configuration and select Manually Provided Keys options. In the approved host keys, select github public key.
3. At first, I thought of using the Docker network to keep the Jenkins container and the registry container on the same network. Then, utilize the registry container name to push/pull and access the local registry. However, I was getting the following error: Get "http://local-registry:5000/v2/": dial tcp: lookup local-registry: Temporary failure in name resolution
  - The GET request of `http://local-registry:5000/v2/` is invoked by the docker push command internally. As we are sharing the host Docker within the Jenkins container, the host machine is unable to resolve the local-registry container name.
  - I didn’t want to create an entry in the host machine to resolve the local-registry to the localhost or its IP address. Because this will lead to an extra step for maintenance.
  - That’s why I decided to use localhost as a registry URL. Since the local registry container and the Jenkins container are running on the same host and the registry container is mapped with the host machine on the 5000 port, we can access the local registry using the `localhost:5000` URL without adding any extra step for maintenance.
4. To verify whether the deployment is successful, I wanted to use the URL http://localhost:3000, but it was refusing the connection.
  - Doing curl on `http://localhost:3000` would actually request the Jenkins container port 3000. However, we ran the Docker image using the docker run command. Since we share the Docker daemon of the host machine, the run command actually mapped the host machine’s port 3000 to the 8080 port of the image. Thus, we need a mechanism to get the host machine’s IP address from the Jenkins container to know whether the deployment is successful or the Docker image is running.
  - In order to access the host machine’s IP address, the extra_hosts key is used in the docker compose under the Jenkins service, and the host_gateway is mapped to host.docker.internal. With host-gateway keyword docker dynamically resolves to the IP address of the gateway of the Docker bridge network on the host machine.
  - Thus, we should curl to http://host.docker.internal:3000 to check whether the deployment is successful.

## Set up automatic build trigger
- Configure Jenkins job
  - Select Pipeline script from SCM.
  - Choose Git as the SCM.
  - Enter your GitHub repository URL.
  - Specify the Branch Specifier (*/main).   
  - Save the job configuration.
- Install ngrok in the host server machine. Follow this [document](https://ngrok.com/docs/getting-started/?os=linux) to install ngrok.
- Configure the GitHub Webhook
  - Go to your GitHub repository.
  - Click on the Settings tab.
  - In the left sidebar, click on Webhooks.
  - Click on the Add webhook button.
  - Payload URL: Enter your Jenkins server's URL followed by /github-webhook/. For example: https://cosmic-puma-hopeful.ngrok-free.app/github-webhook/
  - Content type: Choose application/json.
  - Which events would you like to trigger this webhook? Choose "Just push events".
  - Ensure the Active checkbox is selected.
  - Click on the Add webhook button.
