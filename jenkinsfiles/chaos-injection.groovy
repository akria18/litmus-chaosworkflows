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
                    yum install curl -y
                    curl -LO "https://storage.googleapis.com/kubernetes-release/release/v1.20.5/bin/linux/amd64/kubectl" 
                    chmod u+x ./kubectl
                    git clone https://github.com/akria18/litmus-chaosworkflows.git
                    
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
                                value: "test",
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