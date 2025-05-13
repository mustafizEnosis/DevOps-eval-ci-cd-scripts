## Prerequisites:
- Install Docker Engine on the host machine. Please follow this [document](https://docs.docker.com/engine/install/ubuntu/#install-using-the-repository) to install Docker on the Linux host machine.

## Describing docker-compose.yaml:
- Go to the docker compose file directory.
- Run the docker compose file to run the jenkins and registry containers: `docker compose up`.
- There are two services (Jenkins and registry) in the compose file.
- Jenkins container run as root user to run Docker CLI commands.
- To share the host machine's Docker, /var/run/docker.sock and /usr/bin//docker files are mounted as volumes.
- To persist the jenkins container data, jenkins_home volume is created.
- In order to access the host machine’s IP address, the extra_hosts key is used, and the host_gateway is mapped to host.docker.internal.
- Two ports are mapped with the Jenkins container: 8080 and 50000
- A local registry setup using the public [registry Docker image](https://hub.docker.com/_/registry).
- To persist the registry container data, a registry_data volume is created.
- restart:always key is used to automatically restart the containers if they exit for any reason (except when they are explicitly stopped).

## Setup local registry authentication:
- Install htpasswd
  - `sudo apt install apache2-utils`
- Create a new htpasswd file and add the username
  - sudo htpasswd -c /path/to/.htpasswd <username>
- You'll be prompted to enter and confirm the password for the user. Remember the password for creating credential in Jenkins.
- Add the following 3 environment variables with the registry service.
  1. REGISTRY_AUTH=htpasswd
  2. REGISTRY_AUTH_HTPASSWD_REALM=Registry Realm
  3. REGISTRY_AUTH_HTPASSWD_PATH=/Path/to/htpasswd
- Add the following volume with the registry service.
  - ./Path/to:/auth

## Setup Jenkins:
- As the Jenkins container is running, go to `http://localhost:8080` and provide the admin (username) password found with the below command.
  - sudo docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
- Install the suggested plugins. Additional plugins can be installed from Manage Jenkins -> Manage plugins
- Add credentials: Navigate to Manage Jenkins -> Credentials -> (System) Global Domains -> 
  - GitHub SSH credential: An SSH credential for GitHub is created using this [document](https://docs.github.com/en/authentication/connecting-to-github-with-ssh/generating-a-new-ssh-key-and-adding-it-to-the-ssh-agent?platform=linux). Select SSH Username with private key option and fill data in the fields. Ensure the ID of the credential is DevOps_Repo_SSH
  - Local registry login credential: Select Username with password. In the username field, provide the username used while creating the htpasswd file. Then in the password field, provide the password. Ensure the ID of the credential is DOCKER_REGISTRY_CRED.
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
    - Utilize `withCredentials` to get the username and password of the DOCKER_REGISTRY_CRED credential.
      - Log in to the docker registry: `docker login -u ${REGISTRY_USER} -p ${REGISTRY_PASS} ${REGISTRY_URL}`
    - Push the Docker image to the local registry running at the host machine’s port 5000.
      - `docker push localhost:5000/<IMAGE_NAME>:<COMMIT_SHA>`
    - Remove the local Docker built image using the below command.
      - `docker rmi localhost:5000/<IMAGE_NAME>:<COMMIT_SHA>`
    - Log out from the docker registry: `docker logout ${env.REGISTRY_URL}`
  4. Deploy:
    - Execute this stage if no failure is occurred priorly.
    - Utilize `withCredentials` to get the username and password of the DOCKER_REGISTRY_CRED credential.
      - Log in to the docker registry: `docker login -u ${REGISTRY_USER} -p ${REGISTRY_PASS} ${REGISTRY_URL}`
    - Pull the image from the local registry.
      - `docker pull localhost:5000/${IMAGE_NAME}:${COMMIT_SHA}`
    - Log out from the docker registry: `docker logout ${env.REGISTRY_URL}`
    - Get the container ID of the currently deployed one and stop that.
      - `def container_id = sh(script: "docker ps --filter \"publish=3000\" --format \"{{.ID}}\"", returnStdout: true).trim()`
      - `docker stop ${container_id}`    
    - Run the pulled Docker image using the following command to ensure it is running in detached mode and maps the 8080 port to host machine's 3000 port.
      - `docker run -d -p 3000:8080 localhost:5000/${IMAGE_NAME}:${COMMIT_SHA}`
    - Sleep a little bit to ensure the express server is running within the docker container.
    - Verify whether the server is running by invoking the below curl command and checking whether the response code is 200.
      - `curl --head --silent --write-out \"%{http_code}\" --output /dev/null \"http:\\host.docker.internal:3000\"`

## Issues faced and resolutions
Issue #1. Docker Daemon Socket Permission Denied
- Problem: permission denied while trying to connect to the Docker daemon socket
- Resolution: Needed to add user to the docker group and restart the machine
    - `sudo usermod -aG docker $USER`

Issue #2. Missing 'Known Hosts File' for SSH Host Key Verification in Jenkins
- Problem: You're using 'Known hosts file' strategy to verify ssh host keys, but your known_hosts file does not exist, please go to 'Manage Jenkins' -> 'Security' -> 'Git Host Key Verification Configuration' and configure host key verification.
- Resolution: Navigated to 'Manage Jenkins' -> 'Security' -> 'Git Host Key Verification Configuration' and selected the "Manually Provided Keys" option. Then, added the GitHub public key to the approved host keys.

Issue #3. Local Docker Registry Access Issues within Jenkins Container
- Problem: Initially attempted to use the Docker network to connect the Jenkins and registry containers, intending to use the registry container name (`local-registry`) to push/pull images. This resulted in a DNS resolution error: `Get "http://local-registry:5000/v2/": dial tcp: lookup local-registry: Temporary failure in name resolution`. This occurred because the host Docker is shared, and the host machine couldn't resolve the container name.
- Reasoning against Host Machine Entry: Avoided adding an entry in the host machine's DNS to resolve `local-registry` to localhost or its IP address due to the added maintenance overhead.
- Resolution: Utilized `localhost` as the registry URL. Since both containers reside on the same host, and port 5000 on the host is mapped to the registry container, accessing `localhost:5000` from the jenkins container works without extra configuration.

Issue #4: Difficulty Verifying Deployment via `http://localhost:3000`
- Problem: To verify whether the deployment is successful, I wanted to use the URL http://localhost:3000 through curl from the pipeline script, but it was refusing the connection.
- Explanation: Executing `curl http://localhost:3000` from within the Jenkins container resolves to the container's own localhost, not the host machine's. The Docker `run` command had mapped the host's port 3000 to the image's 8080 port, meaning the application wasn't running on the Jenkins container's port 3000.
- Solution: Added the `extra_hosts` key in the Docker Compose file under the Jenkins service, mapping `host.docker.internal` to the host gateway. The `host-gateway` keyword dynamically resolves to the host machine's Docker bridge network gateway IP. This allows verification of the deployment by curling `http://host.docker.internal:3000)`.

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
  - Payload URL: Enter your Jenkins server's URL (ngrok public URL setup for the jenkins container) followed by /github-webhook/. For example: https://cosmic-puma-hopeful.ngrok-free.app/github-webhook/
  - Content type: Choose application/json.
  - Which events would you like to trigger this webhook? Choose "Just push events".
  - Ensure the Active checkbox is selected.
  - Click on the Add webhook button.
