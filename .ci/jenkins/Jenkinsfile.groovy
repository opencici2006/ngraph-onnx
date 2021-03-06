// INTEL CONFIDENTIAL
// Copyright 2018 Intel Corporation All Rights Reserved.
// The source code contained or described herein and all documents related to the
// source code ("Material") are owned by Intel Corporation or its suppliers or
// licensors. Title to the Material remains with Intel Corporation or its
// suppliers and licensors. The Material may contain trade secrets and proprietary
// and confidential information of Intel Corporation and its suppliers and
// licensors, and is protected by worldwide copyright and trade secret laws and
// treaty provisions. No part of the Material may be used, copied, reproduced,
// modified, published, uploaded, posted, transmitted, distributed, or disclosed
// in any way without Intel's prior express written permission.
// No license under any patent, copyright, trade secret or other intellectual
// property right is granted to or conferred upon you by disclosure or delivery of
// the Materials, either expressly, by implication, inducement, estoppel or
// otherwise. Any license under such intellectual property rights must be express
// and approved by Intel in writing.

// Set LABEL variable if empty or not declared
try{ if(LABEL.trim() == "") {throw new Exception();} }catch(Exception e){LABEL="onnx && ci"}; echo "${LABEL}"
// CI settings and constants
PROJECT_NAME = "ngraph-onnx"            
CI_ROOT = ".ci/jenkins"
DOCKER_CONTAINER_NAME = "jenkins_${PROJECT_NAME}_ci"
REPOSITORY_GIT_ADDRESS = "https://www.github.com/NervanaSystems/${PROJECT_NAME}.git"
JENKINS_GITHUB_CREDENTIAL_ID = "7157091e-bc04-42f0-99fd-dc4da2922a55"
NGRAPH_DIRECTORY="/home"
GIT_PR_AUTHOR_EMAIL=""
GIT_COMMIT_AUTHOR_EMAIL=""
GIT_COMMIT_HASH=""
GIT_COMMIT_SUBJECT=""

def CloneRepository(String jenkins_github_credential_id, String repository_git_address) {
    stage('Clone Repo') {
        checkout([$class: 'GitSCM',
                branches: [[name: "$CHANGE_BRANCH"]],
                doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', timeout: 30]], submoduleCfg: [],
                userRemoteConfigs: [[credentialsId: "${jenkins_github_credential_id}",
                url: "${repository_git_address}"]]])
        GIT_PR_AUTHOR_EMAIL = sh (script: 'git log -1 --pretty="format:%ae" ', returnStdout: true).trim()
        GIT_COMMIT_AUTHOR_EMAIL = sh (script: 'git log -1 --pretty="format:%ce" ', returnStdout: true).trim()
        GIT_COMMIT_HASH = sh (script: 'git log -1 --pretty="format:%H" ', returnStdout: true).trim()
        GIT_COMMIT_SUBJECT = sh (script: 'git log -1 --pretty="format:%s" ', returnStdout: true).trim()
    }
}

def BuildImage(configurationMaps) {
    Closure buildMethod = { configMap ->
        sh """
            ${CI_ROOT}/utils/docker.sh build \
                                --name=${configMap["projectName"]} \
                                --version=${configMap["name"]} \
                                --dockerfile_path=${configMap["dockerfilePath"]}
        """
    }
    UTILS.CreateStage("Build_Image", buildMethod, configurationMaps)
}

def RunDockerContainers(configurationMaps) {
    Closure runContainerMethod = { configMap ->
        UTILS.PropagateStatus("Build_Image", configMap["name"])
        sh """
            mkdir -p ${HOME}/ONNX_CI
            ${CI_ROOT}/utils/docker.sh start \
                                --name=${configMap["projectName"]} \
                                --version=${configMap["name"]} \
                                --container_name=${configMap["dockerContainerName"]} \
                                --volumes="-v ${WORKSPACE}/${BUILD_NUMBER}:/logs -v ${HOME}/ONNX_CI:/home -v ${WORKDIR}:/root"
        """
    }
    UTILS.CreateStage("Run_docker_containers", runContainerMethod, configurationMaps)
}

def PrepareEnvironment(configurationMaps) {
    Closure prepareEnvironmentMethod = { configMap ->
        UTILS.PropagateStatus("Run_docker_containers", configMap["dockerContainerName"])
        sh """
            docker cp ${CI_ROOT}/utils/docker.sh ${configMap["dockerContainerName"]}:/home
            docker exec ${configMap["dockerContainerName"]} bash -c "/root/${CI_ROOT}/prepare_environment.sh"
        """
    }
    UTILS.CreateStage("Prepare_environment", prepareEnvironmentMethod, configurationMaps)
}

def RunToxTests(configurationMaps) {
    Closure runToxTestsMethod = { configMap ->
        UTILS.PropagateStatus("Prepare_environment", configMap["dockerContainerName"])
        sh """
            NGRAPH_WHL=\$(docker exec ${configMap["dockerContainerName"]} bash -c "find  ${NGRAPH_DIRECTORY}/ngraph/python/dist/ -name 'ngraph*.whl' -printf '%Ts\t%p\n' | sort -nr | cut -f2 | head -n1")
            docker exec -e TOX_INSTALL_NGRAPH_FROM=\${NGRAPH_WHL} ${configMap["dockerContainerName"]} tox -c /root
        """
    }
    UTILS.CreateStage("Run_tox_tests", runToxTestsMethod, configurationMaps)
}

def Cleanup(configurationMaps) {
    Closure cleanupMethod = { configMap ->
        sh """
            cd ${HOME}/ONNX_CI
            ./docker.sh chmod --container_name=${configMap["dockerContainerName"]} --directory="/logs" --options="-R 777" || true
            docker exec ${configMap["dockerContainerName"]} rm -rf /root/* || true
            ./docker.sh stop --container_name=${configMap["dockerContainerName"]} || true
            ./docker.sh remove --container_name=${configMap["dockerContainerName"]} || true
            ./docker.sh clean_up || true
            rm -f ${HOME}/ONNX_CI/docker.sh
            rm -rf ${WORKSPACE}/${BUILD_NUMBER}
        """
    }
    UTILS.CreateStage("Cleanup", cleanupMethod, configurationMaps)
}

def Notify() {
    configurationMaps = []
    configurationMaps.add([
        "name": "notify"
    ])
    String notifyPeople = "$GIT_PR_AUTHOR_EMAIL, $GIT_COMMIT_AUTHOR_EMAIL"
    Closure notifyMethod = { configMap ->
        if(currentBuild.result == "FAILURE") {
            emailext (
                subject: "NGraph-Onnx CI: NGraph PR $CHANGE_ID $currentBuild.result!",
                body: """
                    <table style="width:100%">
                        <tr><td>Status:</td> <td>${currentBuild.result}</td></tr>
                        <tr><td>Pull Request Title:</td> <td>$CHANGE_TITLE</td></tr>
                        <tr><td>Pull Request:</td> <td><a href=$CHANGE_URL>$CHANGE_ID</a> </td></tr>
                        <tr><td>Branch:</td> <td>$CHANGE_BRANCH</td></tr>
                        <tr><td>Commit Hash:</td> <td>$GIT_COMMIT_SUBJECT</td></tr>
                        <tr><td>Commit Subject:</td> <td>$GIT_COMMIT_HASH</td></tr>
                        <tr><td>Jenkins Build:</td> <td> <a href=$RUN_DISPLAY_URL> ${BUILD_NUMBER} </a> </td></tr>
                    </table>
                """,
                to: "${notifyPeople}"
            )
        }
    }
    UTILS.CreateStage("Notify", notifyMethod, configurationMaps)
}

def main(String label, String projectName, String projectRoot, String dockerContainerName, String jenkins_github_credential_id, String repository_git_address) {
    node(label) {
        timeout(activity: true, time: 60) {        
            WORKDIR = "${WORKSPACE}/${BUILD_NUMBER}/${PROJECT_NAME}"
            def configurationMaps;
            try {
                dir ("${WORKDIR}") {
                    sh "echo WORKAROUND"
                    CloneRepository(jenkins_github_credential_id, repository_git_address)
                    // Load CI API
                    UTILS = load "${CI_ROOT}/utils/utils.groovy"
                    result = 'SUCCESS'
                    // Create configuration maps
                    configurationMaps = UTILS.GetDockerEnvList(projectName, dockerContainerName, projectRoot)
                    // Execute CI steps
                    BuildImage(configurationMaps)
                    RunDockerContainers(configurationMaps)
                    PrepareEnvironment(configurationMaps)
                    RunToxTests(configurationMaps)
                }
            }
            finally {
                Cleanup(configurationMaps)
                Notify()
            }
        }
    }
}

main(LABEL, PROJECT_NAME, CI_ROOT, DOCKER_CONTAINER_NAME, JENKINS_GITHUB_CREDENTIAL_ID, REPOSITORY_GIT_ADDRESS)
