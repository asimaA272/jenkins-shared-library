// One shared pipeline for all three environments.
//   branch dev   -> dev      : Ingest > Train > Deploy > Test > Notify > alias 'Challenger'
//   branch main  -> preprod  : Load 'Challenger' > Log/Register 'Challenger-pre-test' > Deploy > Test > Notify > alias 'Challenger-post-test'
//   git tag (v*) -> prod     : Load 'Challenger-post-test' > Log/Register 'Champion' > Deploy
def call(Map cfg = [:]) {
    def datasets   = cfg.datasets   ?: ['disease', 'boston']
    def recipients = cfg.recipients ?: 'YOUR_EMAIL@gmail.com'
    def mlflowUri  = cfg.mlflowUri  ?: 'http://127.0.0.1:5000'
    def threshold  = cfg.threshold  ?: '0.85'
    def pythonBin  = cfg.python     ?: 'python3.12'   // pyarrow/mlflow 2.x: no wheels for python 3.14

    def target = env.TAG_NAME ? 'prod' :
                 (env.BRANCH_NAME == 'main' ? 'preprod' :
                 (env.BRANCH_NAME == 'dev' ? 'dev' : 'none'))
    def basePort = [dev: 5100, preprod: 5200, prod: 5300][target] ?: 0

    pipeline {
        agent any
        options { disableConcurrentBuilds() }
        environment { MLFLOW_TRACKING_URI = "${mlflowUri}" }

        stages {
            stage('Setup') {
                when { expression { target != 'none' } }
                steps {
                    echo "Target environment: ${target}  (branch=${env.BRANCH_NAME}, tag=${env.TAG_NAME})"
                    sh """#!/bin/bash
set -e
${pythonBin} -m venv .venv
. .venv/bin/activate
pip install -q --upgrade pip
pip install -q -r requirements.txt
"""
                }
            }

            stage('Data Ingest') {
                when { expression { target != 'none' } }
                steps {
                    script {
                        for (int i = 0; i < datasets.size(); i++) {
                            mlopsPy("src/ingest.py --dataset ${datasets[i]}")
                        }
                    }
                }
            }

            stage('Model Train') {
                when { expression { target == 'dev' } }
                steps {
                    script {
                        for (int i = 0; i < datasets.size(); i++) {
                            mlopsPy("src/train.py --dataset ${datasets[i]} --env dev")
                        }
                    }
                }
            }

            stage('Load & Register Model') {
                when { expression { target == 'preprod' || target == 'prod' } }
                steps {
                    script {
                        def src = (target == 'preprod') ? 'Challenger' : 'Challenger-post-test'
                        def dst = (target == 'preprod') ? 'Challenger-pre-test' : 'Champion'
                        for (int i = 0; i < datasets.size(); i++) {
                            mlopsPy("src/promote.py copy --dataset ${datasets[i]} --env ${target} --src-alias ${src} --new-alias ${dst}")
                        }
                    }
                }
            }

            stage('Model Deploy (mlflow)') {
                when { expression { target != 'none' } }
                steps {
                    script {
                        for (int i = 0; i < datasets.size(); i++) {
                            def ds = datasets[i]
                            def port = basePort + i
                            def sel = (target == 'dev') ? "--version-file artifacts/${ds}_version.txt" :
                                      (target == 'preprod') ? "--alias Challenger-pre-test" : "--alias Champion"
                            mlopsPy("src/deploy.py --dataset ${ds} --port ${port} ${sel}", true)
                        }
                    }
                }
            }

            stage('Model Test') {
                when { expression { target == 'dev' || target == 'preprod' } }
                steps {
                    script {
                        for (int i = 0; i < datasets.size(); i++) {
                            mlopsPy("src/test_model.py --dataset ${datasets[i]} --port ${basePort + i} --threshold ${threshold}")
                        }
                    }
                }
            }

            stage('Update Registry Alias') {
                when { expression { target == 'dev' || target == 'preprod' } }
                steps {
                    script {
                        for (int i = 0; i < datasets.size(); i++) {
                            def ds = datasets[i]
                            if (target == 'dev') {
                                mlopsPy("src/promote.py alias --dataset ${ds} --alias Challenger --version-file artifacts/${ds}_version.txt")
                            } else {
                                mlopsPy("src/promote.py retag --dataset ${ds} --src-alias Challenger-pre-test --new-alias Challenger-post-test")
                            }
                        }
                    }
                }
            }
        }

        post {
            success { script { if (target != 'none') { mlopsNotify('SUCCESS', target, recipients) } } }
            failure { script { if (target != 'none') { mlopsNotify('FAILED', target, recipients) } } }
        }
    }
}
