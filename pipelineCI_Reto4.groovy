pipeline {
    agent any

    stages {
        stage('Get Code') {
            steps {
                echo "Clonando código fuente desde la rama develop..."
                git branch: 'develop', url: 'https://github.com/angelabtte/todo-list-awsCP2.git'

                echo "Descargando archivo de configuración samconfig.toml desde la rama staging..."
                sh '''
                    curl -L https://raw.githubusercontent.com/angelabtte/todo-list-aws-config-CPD/staging/samconfig.toml -o samconfig.toml
                    echo "Contenido de samconfig.toml:"
                    cat samconfig.toml
                '''
            }
        }

        stage('Static Test') {
            steps {
                dir('src') {
                    sh 'flake8 . --output-file=flake8-report.txt || true'
                    sh 'bandit -r . -f txt -o bandit-report.txt || true'
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'src/flake8-report.txt, src/bandit-report.txt', allowEmptyArchive: true
                }
            }
        }

        stage('Deploy') {
            environment {
                STAGE = 'staging'
            }
            steps {
                echo "Desplegando aplicación con AWS SAM en entorno ${env.STAGE}..."

                sh '''
                    echo "Estructura del workspace:"
                    ls -la
                    echo "Contenido de src:"
                    ls -la src
                '''

                sh '''
                    sam build --template-file template.yaml
                    sam deploy
                '''
            }
        }

        stage('Rest Test') {
            steps {
                echo "Ejecutando pruebas de integración REST con pytest en entorno staging..."
                sh '''
                    pip install pytest requests

                    echo "Obteniendo la URL base de la API desplegada..."
                    BASE_URL=$(aws cloudformation describe-stacks --stack-name staging-todolistAWS3 \
                        --query "Stacks[0].Outputs[?OutputKey=='BaseUrlApi'].OutputValue" --output text)

                    echo "URL pública detectada: $BASE_URL"

                    BASE_URL=$BASE_URL PATH=$PATH:~/.local/bin pytest test/integration/todoApiTest.py \
                        --maxfail=1 --disable-warnings -q \
                        --junitxml=rest-test-report.xml
                '''
            }
            post {
                always {
                    junit 'rest-test-report.xml'
                }
            }
        }

        stage('Promote') {
            steps {
                echo "Promoviendo versión a producción: merge de 'develop' a 'master'..."

                sh '''
                    git config --global user.email "angel.bts12@gmail.com"
                    git config --global user.name "angelabtte"
                '''

                withCredentials([usernamePassword(credentialsId: 'tokencp3', usernameVariable: 'angelabtte', passwordVariable: 'tokencp3')]) {
                    sh '''
                        git remote set-url origin https://${angelabtte}:${tokencp3}@github.com/angelabtte/todo-list-awsCP2.git
                        git fetch origin
                        git checkout master || git checkout -b master origin/develop
                        git merge origin/develop -m "Promoción automática a producción desde Jenkins"
                        git push origin master
                    '''
                }
            }
        }
    }
}
