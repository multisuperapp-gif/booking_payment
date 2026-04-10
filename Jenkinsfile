pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    parameters {
        choice(
                name: 'TARGET_ENV',
                choices: ['DEV', 'SIT', 'UAT', 'PROD'],
                description: 'Select environment to deploy'
        )
    }

    environment {
        PATH = "/Applications/Docker.app/Contents/Resources/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
        APP_NAME = "booking-payment"
        CONTAINER_NAME = "booking-payment"
        APP_PORT = "8084"
        IMAGE_TAG = "${APP_NAME}:${BUILD_NUMBER}"
        IMAGE_LATEST = "${APP_NAME}:latest"

        EC2_HOST = "44.207.68.180"
        EC2_USER = "ubuntu"
        EC2_APP_DIR = "/home/ubuntu/booking-payment"

        DEV_EC2_HOST  = "44.207.68.180"
        SIT_EC2_HOST  = "44.207.68.180"
        UAT_EC2_HOST  = "44.207.68.180"
        PROD_EC2_HOST = "44.207.68.180"

        DEV_PROFILE  = "dev"
        SIT_PROFILE  = "sit"
        UAT_PROFILE  = "uat"
        PROD_PROFILE = "prod"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Verify Tooling') {
            steps {
                sh '''
                    export JAVA_HOME=$(/usr/libexec/java_home -v 21)
                    export PATH="$JAVA_HOME/bin:$PATH"
                    java -version
                    mvn -version
                '''
            }
        }

        stage('Clean') {
            steps {
                sh '''
                    export JAVA_HOME=$(/usr/libexec/java_home -v 21)
                    export PATH="$JAVA_HOME/bin:$PATH"
                    mvn -B clean
                '''
            }
        }

        stage('Compile') {
            steps {
                sh '''
                    export JAVA_HOME=$(/usr/libexec/java_home -v 21)
                    export PATH="$JAVA_HOME/bin:$PATH"
                    mvn -B compile
                '''
            }
        }

        stage('Unit Test') {
            steps {
                sh '''
                    export JAVA_HOME=$(/usr/libexec/java_home -v 21)
                    export PATH="$JAVA_HOME/bin:$PATH"
                    mvn -B test
                '''
            }
        }

        stage('Package') {
            steps {
                sh '''
                    export JAVA_HOME=$(/usr/libexec/java_home -v 21)
                    export PATH="$JAVA_HOME/bin:$PATH"
                    mvn -B package -DskipTests
                '''
            }
        }

        stage('Approval for SIT') {
            when {
                expression { params.TARGET_ENV == 'SIT' }
            }
            steps {
                input message: 'Approve deployment to SIT?', ok: 'Deploy to SIT'
            }
        }

        stage('Approval for UAT') {
            when {
                expression { params.TARGET_ENV == 'UAT' }
            }
            steps {
                input message: 'Approve deployment to UAT?', ok: 'Deploy to UAT'
            }
        }

        stage('Approval for PROD') {
            when {
                expression { params.TARGET_ENV == 'PROD' }
            }
            steps {
                input message: 'Approve deployment to PROD?', ok: 'Deploy to PROD'
            }
        }

        stage('Deploy to DEV') {
            when {
                expression { params.TARGET_ENV == 'DEV' }
            }
            steps {
                script {
                    deployToEnvironment(env.DEV_EC2_HOST, env.DEV_PROFILE, 'DEV')
                }
            }
        }

        stage('Health Check DEV') {
            when {
                expression { params.TARGET_ENV == 'DEV' }
            }
            steps {
                script {
                    healthCheck(env.DEV_EC2_HOST, 'DEV')
                }
            }
        }

        stage('Deploy to SIT') {
            when {
                expression { params.TARGET_ENV == 'SIT' }
            }
            steps {
                script {
                    deployToEnvironment(env.SIT_EC2_HOST, env.SIT_PROFILE, 'SIT')
                }
            }
        }

        stage('Health Check SIT') {
            when {
                expression { params.TARGET_ENV == 'SIT' }
            }
            steps {
                script {
                    healthCheck(env.SIT_EC2_HOST, 'SIT')
                }
            }
        }

        stage('Deploy to UAT') {
            when {
                expression { params.TARGET_ENV == 'UAT' }
            }
            steps {
                script {
                    deployToEnvironment(env.UAT_EC2_HOST, env.UAT_PROFILE, 'UAT')
                }
            }
        }

        stage('Health Check UAT') {
            when {
                expression { params.TARGET_ENV == 'UAT' }
            }
            steps {
                script {
                    healthCheck(env.UAT_EC2_HOST, 'UAT')
                }
            }
        }

        stage('Deploy to PROD') {
            when {
                expression { params.TARGET_ENV == 'PROD' }
            }
            steps {
                script {
                    deployToEnvironment(env.PROD_EC2_HOST, env.PROD_PROFILE, 'PROD')
                }
            }
        }

        stage('Health Check PROD') {
            when {
                expression { params.TARGET_ENV == 'PROD' }
            }
            steps {
                script {
                    healthCheck(env.PROD_EC2_HOST, 'PROD')
                }
            }
        }
    }

    post {
        always {
            junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
            archiveArtifacts artifacts: 'target/*.jar', fingerprint: true, onlyIfSuccessful: true
        }
    }
}

def deployToEnvironment(String host, String springProfile, String envName) {
    sshagent(credentials: ['ec2-ssh-key']) {
        sh """
            ssh -o StrictHostKeyChecking=no ${env.EC2_USER}@${host} "mkdir -p ${env.EC2_APP_DIR}"

            scp -o StrictHostKeyChecking=no target/*.jar ${env.EC2_USER}@${host}:${env.EC2_APP_DIR}/app.jar
            scp -o StrictHostKeyChecking=no Dockerfile ${env.EC2_USER}@${host}:${env.EC2_APP_DIR}/Dockerfile
        """

        sh """
            ssh -o StrictHostKeyChecking=no ${env.EC2_USER}@${host} "
                cd ${env.EC2_APP_DIR} && \
                docker rm -f ${env.CONTAINER_NAME} || true && \
                docker build -t ${env.IMAGE_TAG} . && \
                docker tag ${env.IMAGE_TAG} ${env.IMAGE_LATEST} && \
                docker run -d \
                  --name ${env.CONTAINER_NAME} \
                  --restart unless-stopped \
                  -p ${env.APP_PORT}:${env.APP_PORT} \
                  --env-file ${env.EC2_APP_DIR}/${springProfile}.env \
                  -e SERVER_PORT=${env.APP_PORT} \
                  -e SPRING_PROFILES_ACTIVE=${springProfile} \
                  ${env.IMAGE_TAG}
            "
        """

        echo "${envName} deployment completed successfully."
    }
}

def healthCheck(String host, String envName) {
    sh """
        echo "Running health check for ${envName}..."

        for i in {1..18}; do
            STATUS=\$(curl -s http://${host}:${env.APP_PORT}/actuator/health | grep -o '"status":"UP"' || true)

            if [ ! -z "\$STATUS" ]; then
                echo "${envName} application is UP"
                exit 0
            fi

            echo "Waiting for ${envName} application to become healthy... attempt \$i/18"
            sleep 10
        done

        echo "${envName} application failed health check"
        exit 1
    """
}
