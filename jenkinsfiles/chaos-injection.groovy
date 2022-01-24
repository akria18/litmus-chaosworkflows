#!/usr/bin/env groovy
def colors = [
    SUCCESS: 'good',
    FAILURE: '#e81f3f',
    ABORTED: 'warning',
    UNSTABLE: 'warning'
]

def slackChannel = 'sre-and-chaos-engineering'
def decodedJobName = env.JOB_NAME.replaceAll('%2F', '/')

pipeline {
    environment {
		DOCKERHUB_CREDENTIALS=credentials('chaoscarnival22')
        DOCKER_DEV_PATH = "chaoscarnival22/dev/"
        DOCKER_PROD_PATH = "chaoscarnival22/prod/"
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
                            VERSION_SUFFIX = ''
                            if(BRANCH_NAME != 'master') {
                                VERSION_SUFFIX="-${BRANCH_NAME}"
                            }
                            VERSION_SUFFIX = "${VERSION_SUFFIX}-BUILD-${BUILD_NUMBER}"
                            env.DOCKER_IMAGE_TAG = "${VERSION_SUFFIX}"
                            env.APP_DOCKER_IMAGE = "${DOCKER_DEV_PATH}chaoscanrival-demo:${DOCKER_IMAGE_TAG}"                       
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
                    sh '''
                    echo $DOCKERHUB_CREDENTIALS_PSW | docker login -u $DOCKERHUB_CREDENTIALS_USR --password-stdin
                    kubectl -napp set deployment/chaoscarnival-demo chaoscarnival-demo=chaoscarnival22/chaoscarnival-demo:1.0.0
                    
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
        stage('inject chaos') {
            steps {
                container('chaos-builder') {
                    sh '''
                    echo "unleash the chaos => CPU hogging"
                    ./kubectl apply -f  litmus-chaosworkflows/workflows/
                    ./scripts/cleanup.sh
                    '''
                    
                }
                script {
                    chaosResults  = readFile('report.txt').trim()
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
                                title: "Tag",
                                value: "ChaosEngineering",
                                short: true
                            ],
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