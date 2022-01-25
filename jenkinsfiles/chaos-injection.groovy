#!/usr/bin/env groovy
def colors = [
    SUCCESS: 'good',
    FAILURE: '#e81f3f',
    ABORTED: 'warning',
    UNSTABLE: 'warning'
]

def slackChannel = 'sre-and-chaos-engineering'
def decodedJobName = env.JOB_NAME.replaceAll('%2F', '/')
def chaosResults = ""
def chaosResult = ""
pipeline {
    environment {
		DOCKERHUB_CREDENTIALS=credentials('chaoscarnival22')
        DOCKER_DEV_PATH = "chaoscarnival22/dev"
        DOCKER_PROD_PATH = "chaoscarnival22/prod"
        DOCKER_IMAGE_PREFIX = "chaoscarnival-demo"
	}
    agent {
        kubernetes {
            label 'kube-agent'
            yaml '''
            apiVersion: v1
            kind: Pod
            spec:
                serviceAccountName: jenkins-admin
                containers:
                - name: chaos-builder
                  image: jenkinsxio/builder-base:0.1.275
                  command:
                  - cat
                  tty: true
                  volumeMounts:
                  - name: docker
                    mountPath: /var/run/docker.sock
                volumes:
                - name: docker
                  hostPath:
                    path: /var/run/docker.sock
            '''
        }
    }

    stages {
        stage('Prepare env ') {
            steps {
                container('chaos-builder') {
                    script {
                            DATE_VERSION = new Date().format('yyyyMMdd')
                            VERSION_SUFFIX = 'demo'
                            if(BRANCH_NAME != 'master') {
                                VERSION_SUFFIX="-${BRANCH_NAME}"
                            }
                            VERSION_SUFFIX = "${VERSION_SUFFIX}-BUILD-${BUILD_NUMBER}"
                            env.DOCKER_IMAGE_TAG = "${VERSION_SUFFIX}"
                            env.APP_DOCKER_IMAGE_DEV = "${DOCKER_DEV_PATH}-chaoscarnival-demo:${DOCKER_IMAGE_TAG}"
                            env.APP_DOCKER_IMAGE_PROD = "${DOCKER_PROD_PATH}-chaoscarnival-demo:${DOCKER_IMAGE_TAG}"         
                            triggerDesc = currentBuild.getBuildCauses().get(0).shortDescription
                            slackSend (
                                channel: "${slackChannel}",
                                attachments: [[
                                    title: "${decodedJobName}, build #${env.BUILD_NUMBER}",
                                    title_link: "${env.BUILD_URL}",
                                    color: '#11aac4',
                                    text: "Started",
                                    fields: [
                                        [
                                            title: "Trigger",
                                            value: "${triggerDesc}",
                                            short: true
                                        ]
                                    ]
                                ]]
                            )
                        }
                }
                
            }
        }
        stage('Build image and push it to dev') {
            steps {
                container('chaos-builder') {
                    sh '''
                    echo $DOCKERHUB_CREDENTIALS_PSW | docker login -u $DOCKERHUB_CREDENTIALS_USR --password-stdin
                    cd app 
                    docker build \
                            --network=host \
                            --tag ${APP_DOCKER_IMAGE_DEV} .
                    docker push ${APP_DOCKER_IMAGE_DEV}
                    '''
                    
                }  
            }
        }
        stage('QA testing') {
            steps {
                container('chaos-builder') {
                    sh '''
                    echo "QA testing"
                    '''
                    
                }  
            }
        }
        stage('Updating the app with new image and inject chaos') {
            steps {
                container('chaos-builder') {
                    sh '''
                    echo "update the app with new image"  
                    kubectl -napp  set image  deployment/${DOCKER_IMAGE_PREFIX} ${DOCKER_IMAGE_PREFIX}=${APP_DOCKER_IMAGE_DEV}
                    kubectl wait --for=condition=available --timeout=600s deployment/${DOCKER_IMAGE_PREFIX} -n app
                    
                    echo "unleash the chaos => CPU hogging"
                    ./scripts/chaos.sh
                    '''
                    
                }
                script {
                    chaosResults  = readFile('report.txt').trim()
                    chaosResult=sh returnStdout: true, script: 'grep -q "Fail" report.txt; test $? -eq 0 && printf "Fail" || echo "Succeeded"'

                }
                
            }
        }
        stage('Promote image') {
            when {
                expression { chaosResult == 'Pass' }
            }
            steps {
                container('chaos-builder') {
                    sh '''
                    echo $DOCKERHUB_CREDENTIALS_PSW | docker login -u $DOCKERHUB_CREDENTIALS_USR --password-stdin
                    docker tag ${APP_DOCKER_IMAGE_DEV} ${APP_DOCKER_IMAGE_PROD} 
                    docker push ${APP_DOCKER_IMAGE_PROD} 
                    '''
                    
                }  
            }
        }

    }
    post {
        always {
            script {
                triggerDesc = currentBuild.getBuildCauses().get(0).shortDescription
                attachments = [
                    [
                        title: "${env.JOB_NAME}, build #${env.BUILD_NUMBER}",
                        title_link: "${env.BUILD_URL}",
                        color: colors[currentBuild.result],
                        text: "${currentBuild.result}",
                        fields: [
                            [
                                title: "Trigger",
                                value: "${triggerDesc}",
                                short: true
                            ],
                            [
                                title: "Chaos Results",
                                value: "${chaosResults}",
                                short: true
                            ],
                            [
                                title: "Duration",
                                value: "${currentBuild.durationString}",
                                short: true
                            ]
                        ]
                    ]
                ]
            }
        }

        success {
            script {
                slackSend (
                    channel: "${slackChannel}",
                    attachments: attachments
                )
            }
        }

        unstable {
            script {
                slackSend (
                    channel: "${slackChannel}",
                    attachments: attachments
                )
            }
        }

        failure {
            script {
                slackSend (
                    channel: "${slackChannel}",
                    attachments: attachments
                )
            }
        }

        aborted {
            script {
                slackSend (
                    channel: "${slackChannel}",
                    attachments: attachments
                )
            }
        }
    }
}