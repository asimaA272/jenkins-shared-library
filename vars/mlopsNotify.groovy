// Email notification (needs the "Email Extension" plugin + SMTP configured in Jenkins).
def call(String status, String target, String recipients) {
    emailext(
        to: recipients,
        subject: "[MLOps][${target}] ${status}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
        mimeType: 'text/plain',
        body: """Pipeline : ${env.JOB_NAME}
Build    : #${env.BUILD_NUMBER}
Branch/Tag: ${env.TAG_NAME ?: env.BRANCH_NAME}
Environment: ${target}
Status   : ${status}
Details  : ${env.BUILD_URL}
"""
    )
}
