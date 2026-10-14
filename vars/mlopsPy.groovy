// Run a python script from the project inside the workspace virtualenv.
// usage: mlopsPy('src/train.py --dataset disease --env dev')
//        mlopsPy('src/deploy.py ...', true)   // keep serving process alive after build
def call(String cmd, boolean keepAlive = false) {
    def run = {
        sh """#!/bin/bash
set -e
. .venv/bin/activate
python ${cmd}
"""
    }
    if (keepAlive) {
        // stop Jenkins' ProcessTreeKiller from killing the model server when the build ends
        withEnv(['JENKINS_NODE_COOKIE=dontKillMe', 'BUILD_ID=dontKillMe']) { run() }
    } else {
        run()
    }
}
