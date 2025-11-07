// 加载共享库
@Library("mylib@main") _

// 导入库
import org.devops.*

// New实例化
def checkout = new Checkout()
def build = new Build()
def notice = new Notice()
def unitTest = new UnitTest()
def custom = new Custom()
def codeScan = new CodeScan()
def gitlab = new GitLab()
def artifact = new Artifact()
def docker = new Docker()
def kubernetes = new Kubernetes()
def projectCustom = new ProjectCustom()

pipeline {
    agent { label "build" }

    options {
        skipDefaultCheckout true
        timestamps()
        buildDiscarder logRotator(daysToKeepStr: '180', numToKeepStr: '90')
        timeout(time: 1, unit: 'HOURS') // 设置全局超时时间
    }

    parameters {
        choice choices: ['git@localhost:devops/devops-web-be.git',
                         'git@localhost:devops/devops-web-fe.git',
        ], description: 'GitLab代码仓库地址', name: 'srcUrl'
        choice(name: 'refType', choices: ['branch', 'tags'], description: '请选择类型：branch=分支，tags=标签')
        string(name: 'refName', defaultValue: 'v2.8.0', description: '填写分支或标签名')
        choice choices: ['dev01', 'dev02', 'test01', 'test02', 'test03'], description: '环境列表', name: 'envList'
        choice choices: ['2048Mi', '128Mi', '4096Mi', '8192Mi', '16384Mi'], description: '最大内存', name: 'memory'
        choice choices: ['1', '2', '3', '4', '5'], description: '副本数', name: 'replicaCount'
        choice choices: ['true', 'false'], description: '是否跳过DB更新', name: 'skipLiquibase'
        choice choices: ['true', 'false'], description: '是否跳过代码扫描', name: 'skipScans'
        choice choices: ['true', 'false'], description: '是否跳过单元测试', name: 'skipTests'
        choice choices: ['false', 'true'], description: '是否跳过CD', name: 'skipCD'
    }

    environment {
        // GitLab用户密钥访问凭据Id：id_ed25519 (GitLab-Enterprise-私钥文件（192.168.100.102:/root/.ssh/id_ed25519）)
        gitlabKeysCredentialsId = "a7d76450-d876-44a8-8d96-92f11cd013b0"
        // GitLab用户密码访问凭据Id：GitLab-ziming.xing-用户密码（gitserv.proaimltd.com.cn）
        gitUserPWDCredentialsId = "a6b079ef-64cc-4c54-a342-6aee6d42a898"
        // GitLab用户Token访问凭据Id：GitLab-DevOps-token（Your_GitLab_Enterprise_Edition_URL，users：devops）
        gitlabUserTokenCredentialsId = "36e10c3d-997d-4eaa-9e46-d9848d5d6631"
        // 制品仓库地址
        artifactRegistry = "192.168.100.150:8081"
        // 制品仓库访问凭据Id：Nexus-admin-账号密码（192.168.100.150:8081）
        artifactCredentialsId = "adfe55cc-1f4a-444a-9c9f-7fc635c46a3c"
        // 制品仓库名称
        artifactRepository = "devops-artifacts"
        // 镜像仓库地址
        imageRegistry = "192.168.100.150:8082"
        // 镜像仓库访问凭据Id：Harbor-admin-账号密码（192.168.100.150:8082）
        imageRegistryCredentialsId = "cc81ccc9-962f-42ab-bbe6-fa9383c6938f"
        // SonarQube访问凭据Id：SonarQube-admin-token（192.168.100.150:9000）
        sonarqubeUserTokenCredentialsId = "c23d40dd-a6c8-4a17-a0d1-23dd795fe773"
        // DingTalk-robot-token（Jenkins钉钉群聊）
        dingTalkTokenCredentialsId = "8c6083c7-e1c2-47c0-9367-b67a9469bcd5"
        // DingTalk-robot-id（Jenkins钉钉群聊）
        dingTalkRebotIdCredentialsId = "5213e392-d78e-4a9a-a37e-91f394309df1"

        // 测试报告路径（多模块使用 "**" ，单模块去掉 "**/" 即可）
        reportsPath = '**/target/surefire-reports/*.xml'
        // Allure报告路径
        allureResultsPath = '**/target/allure-results'

        // helm info
        helmSrcUrl = "git@localhost:devops/devops-k8s-deployment.git"
        helmBranchName = "main"

        // Liquibase
        dbSrcUrl = "git@localhost:devops/devops-db.git"
        liquibaseFiles = "rcm_upgrade_schema.sql|rcm_upgrade_data.sql"
    }

    stages {

        stage('Clean Workspace') { steps { cleanWs() } }

        stage("Checkout") {
            steps {
                script {
                    println("Checkout")
                    checkout.GetCode("${params.srcUrl}", "${params.refName}", "${params.refType}", "${env.gitUserPWDCredentialsId}")
                }
            }
        }

        stage("Global") {
            steps {
                script {
                    // buName（业务名称/组名称） & serviceName
                    env.buName = "${params.srcUrl}".split(':')[1].split('/')[0]
                    println("buName：${env.buName}")
                    // 服务名称
                    env.serviceName = "${params.srcUrl}".split('/')[-1].replaceFirst('\\.git$', '')
                    println("serviceName：${env.serviceName}")
                    // 先获取 serviceName 的最后一个 - 的位置
                    def lastDashIndex = "${env.serviceName}".lastIndexOf('-')
                    println("lastDashIndex：${lastDashIndex}")
                    // 再获取 serviceName 倒数第二个 - 的位置
                    def secondLastDashIndex = "${env.serviceName}".lastIndexOf('-', lastDashIndex - 1)
                    println("secondLastDashIndex：${secondLastDashIndex}")
                    // 截取末尾两个 - 的字符串（模块名称、模块类型，例：product-web-be）
                    env.productName = "${env.serviceName}".substring(0, secondLastDashIndex)
                    println("productName：${env.productName}")
                    // 获取模块名称（例：网站 = web、同步 = sync）
                    env.moduleName = "${env.serviceName}".split('-')[-2]
                    println("moduleName：${env.moduleName}")
                    // 获取模块类型（例：后端 = be、前端 = fe）
                    env.moduleType = "${env.serviceName}".split('-')[-1]
                    println("moduleType：${env.moduleType}")

                    // project id (try local map first)
                    env.projectId = projectCustom.getProjectIdByProjectName("${env.serviceName}")
                    if ("${env.projectId}" == "null") {
                        env.projectId = gitlab.GetProjectId("${env.gitlabUserTokenCredentialsId}", "${env.buName}", "${env.serviceName}")
                    }
                    println("serviceName：${env.serviceName}，projectId：${env.projectId}")

                    // commit id and web url
                    env.commitId = gitlab.GetShortCommitIdByEightDigit()
                    env.commitWebURL = gitlab.GetCommitWebURLByApi("${env.gitlabUserTokenCredentialsId}", "${env.projectId}", "${params.refName}")
                    // 服务版本号（推荐定义："${refName}-${commitId}"）
                    env.version = "${params.refName}-${env.commitId}"

                    // domain names
                    if ("${env.moduleType}" == "be") {
                        env.accessDomainName = "${params.envList}.${env.productName}-${env.moduleName}-fe.int.proaimltd.com.cn"
                    } else {
                        env.accessDomainName = "${params.envList}.${env.serviceName}.int.proaimltd.com.cn"
                    }
                    env.domainName = "${params.envList}.${env.serviceName}.int.proaimltd.com.cn"
                    env.namespace = "${env.productName}-${params.envList}"

                    // update build display and desc
                    currentBuild.description = """refName：${params.refName} \n serviceName：${env.serviceName} \n namespace：${env.namespace} \n commitId：[${env.commitId}](${env.commitWebURL}) \n domainName： \n ${env.domainName} \n accessDomainName：${env.accessDomainName}"""
                    currentBuild.displayName = "${env.version}"
                }
            }
        }

        stage("Build") {
            steps {
                script {
                    println("Build")
                    projectCustom.executeBuildByServiceName("${env.serviceName}")
                }
            }
        }

        stage("UnitTest") {
            when {
                anyOf {
                    allOf {
                        environment name: 'skipTests', value: 'false'
                        environment name: 'moduleType', value: 'be'
                    }
                }
            }
            steps {
                script {
                    println("=== UnitTest Stage ===")

                    catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                        switch ("${env.serviceName}") {
                            case "devops-web-be":
                                // 后端逻辑：执行单元测试并生成报告
                                sh """
                                    mvn clean install -DskipTests
                                    mvn -pl devops-entrypoint test
                                    mkdir -p ${env.allureResultsPath}
                                """
                                // 归档与测试报告（仅后端）
                                archiveArtifacts artifacts: "${env.reportsPath}", allowEmptyArchive: true
                                junit "${env.reportsPath}"

                                println("AllureReport")
                                allure includeProperties: false, jdk: '', results: [[path: "${env.allureResultsPath}"]]
                                break
                            case "devops-web-fe":
                                // 前端逻辑：仅执行简单单元测试，不生成报告
                                println("Frontend UnitTest (no reports)")
                                unitTest.CodeTest("yarn")
                                break
                            default:
                                error "Unsupported productName: ${env.productName}"
                                break
                        }
                    }
                }
            }
        }

        stage("CodeScan") {
            when {
                anyOf {
                    allOf {
                        environment name: 'skipScans', value: 'false'
                    }
                    // 即使不是后端模块，只要是前端服务也必须执行单测
                    expression { env.serviceName == "devops-web-fe" }
                }
            }
            steps {
                script {
                    println("CodeScan")
                    try {
                        switch ("${env.serviceName}") {
                            case "devops-web-fe":
                                println("Running CodeScan...")
                                nodejs('nodejs-18') {
                                    codeScan.checkVueTscLint()
                                }
                                break
                            default:
                                // 代码扫描 commit-status
                                codeScan.CodeScan_Sonar("${env.sonarqubeUserTokenCredentialsId}", "${env.gitlabUserTokenCredentialsId}",
                                        "${params.refName}", "${env.commitId}", "${env.projectId}")
                                break
                        }
                    } catch (Exception e) {
                        // 捕捉异常并输出错误信息，但不影响其他阶段的执行
                        echo "CodeScan failed: ${e.getMessage()}"
                        // 可选：将失败信息保存到日志中，或者标记失败
                        currentBuild.result = 'SUCCESS'  // 让整个构建结果保持成功
                    }
                }
            }
        }

        stage("Run Liquibase via Docker") {
            when {
                anyOf {
                    allOf {
                        environment name: 'skipLiquibase', value: 'false'
                        environment name: 'moduleType', value: 'be'
                    }
                }
            }
            steps {
                script {
                    sh "[ -d Liquibase ] || mkdir Liquibase"
                    ws("${WORKSPACE}/Liquibase") {
                        checkout.GetCode("${env.dbSrcUrl}", "main", "${env.gitUserPWDCredentialsId}")

                        env.upgradeDir = "${env.WORKSPACE}/${env.productName}/${params.refName}/upgrade"
                        // 按 | 分割文件
                        def files = env.liquibaseFiles.split("\\|").collect { it?.trim() }
                        def searchPath = "/workspace/${env.productName}/${params.refName}/upgrade"
                        println("📦 refName:${params.refName}，📂 Upgrade Dir: ${env.upgradeDir}，📂 WORKSPACE: ${env.WORKSPACE}，🔍 SearchPath: ${searchPath}，files: ${files}")

                        // 获取数据库配置
                        def getDbConfig = { envName ->
                            def map = [
                                    dev01 : [url: "jdbc:mysql://192.168.100.108:3306/devops_dev01", user: "root", pass: "proaim@2013"],
                                    dev02 : [url: "jdbc:mysql://192.168.100.109:3306/devops_dev02", user: "root", pass: "proaim@2013"],
                                    test01: [url: "jdbc:mysql://192.168.100.111:3306/devops_test01", user: "root", pass: "proaim@2013"],
                                    test02: [url: "jdbc:mysql://192.168.100.198:3306/devops_test02", user: "root", pass: "proaim@2013"],
                                    test03: [url: "jdbc:mysql://192.168.100.112:3306/devops_test03", user: "root", pass: "proaim@2013"],
                            ]
                            return map[envName] ?: error("❌ 未找到环境 '${envName}' 的数据库配置")
                        }
                        def cfg = getDbConfig(params.envList)
                        env.DB_URL = cfg.url
                        env.DB_USER = cfg.user
                        env.DB_PASS = cfg.pass
                        echo "✅ 已加载数据库配置：${params.envList}，JDBC URL: ${env.DB_URL}, User: ${env.DB_USER}"

                        // 安全检查文件是否存在
                        def safeFileExists = { String path ->
                            try {
                                return fileExists(path)
                            } catch (err) {
                                def out = sh(script: "[ -f \"${path}\" ] && echo true || echo false", returnStdout: true).trim()
                                return out == 'true'
                            }
                        }

                        files.eachWithIndex { changeLogFile, idx ->
                            def hostPath = "${env.upgradeDir}/${changeLogFile}"
                            if (safeFileExists(hostPath)) {
                                echo "📄 执行文件 ${idx + 1}/${files.size()}: ${hostPath}"
                                sh """
                                    docker run --rm \
                                      -e TZ=Asia/Shanghai \
                                      -e INSTALL_MYSQL=true \
                                      -v "${env.WORKSPACE}:/workspace" \
                                      --network host \
                                      liquibase/liquibase:5.0.1 \
                                      --searchPath='${searchPath}' \
                                      --url='${env.DB_URL}' \
                                      --username='${env.DB_USER}' \
                                      --password='${env.DB_PASS}' \
                                      --changeLogFile='${changeLogFile}' \
                                      update
                                """
                            } else {
                                echo "⚠️ 跳过不存在的文件: ${hostPath}"
                            }
                        }
                    }
                }
            }
        }

        // 上传制品（Format：raw） - 支持 devops 多服务 & 通用后端/前端
        stage("PushArtifact") {
            steps {
                script {
                    // handle devops special case first
                    // backend single-service generic flow
                    env.buildType = env.buildType ?: "mavenSkip"
                    env.filePath = env.filePath ?: "target"
                    env.fileSuffix = env.fileSuffix ?: "jar"
                    env.newFileName = "${env.serviceName}-${env.version}.${env.fileSuffix}"
                    if ("${env.serviceName}" == "devops-web-be") {
                        def services = projectCustom.getServiceList("${env.serviceName}")
                        println("Processing devops services: ${services}")

                        services.each { svc ->
                            // expect original artifact name like: ${svc}-0.1.0.jar
                            sh """
                                cd ${env.filePath}
                                if [ -f ${svc}-0.1.0.${env.fileSuffix} ]; then
                                  mv ${svc}-0.1.0.${env.fileSuffix} ${svc}-${env.version}.${env.fileSuffix}
                                else
                                  echo "ERROR: expected artifact ${svc}-0.1.0.${env.fileSuffix} not found"
                                  ls -lah ${env.filePath} || true
                                  exit 1
                                fi
                            """
                            artifact.PushArtifactByApi("${env.artifactRegistry}", "${env.artifactCredentialsId}", "${env.artifactRepository}",
                                    "${env.buName}/${svc}/${env.version}", "${env.buildType}",
                                    "${env.filePath}", "${svc}-${env.version}.${env.fileSuffix}")
                        }
                    } else if (["be", "backend"].contains("${env.moduleType}")) {
                        // find built artifact
                        def original = sh(returnStdout: true, script: "ls ${env.filePath} | grep -E '\\.${env.fileSuffix}\$' || true").trim()
                        if (!original) {
                            error "No artifact found in ${env.filePath} with suffix ${env.fileSuffix}"
                        }
                        // pick first line if multiple - 使用 split 替代 tokenize
                        def lines = original.split('\n')
                        def originalFileName = lines[0].trim()
                        sh "cd ${env.filePath} && mv '${originalFileName}' '${env.newFileName}'"
                        artifact.PushArtifactByApi("${env.artifactRegistry}", "${env.artifactCredentialsId}", "${env.artifactRepository}",
                                "${env.buName}/${env.serviceName}/${env.version}", "${env.buildType}", "${env.filePath}", "${env.newFileName}")
                    } else if ("${env.moduleType}" == "fe") {
                        env.filePath = "dist"
                        env.fileSuffix = "tar.gz"
                        // 必须重新赋值 newFileName
                        env.newFileName = "${env.serviceName}-${env.version}.${env.fileSuffix}"
                        sh "cd ${env.filePath} && tar -zcvf ${env.newFileName} *"
                        artifact.PushArtifactByApi("${env.artifactRegistry}", "${env.artifactCredentialsId}", "${env.artifactRepository}",
                                "${env.buName}/${env.serviceName}/${env.version}", "${env.buildType}", "${env.filePath}", "${env.newFileName}")
                    } else {
                        // fallback: allow project to provide its own script
                        env.result = sh(returnStdout: true, script: "sh artifact.sh ${env.filePath} ${env.serviceName} ${env.version}").trim()
                        env.newFileName = "${env.result}"
                        println("通过项目内自定义脚本上传制品 -> ${env.newFileName}")
                    }
                }
            }
        }

        // Docker build & push (use single lock for images)
        stage("DockerBuild") {
            steps {
                script {
                    def services = projectCustom.getServiceList("${env.serviceName}")
                    println("Docker building services: ${services}")

                    // single lock covering all builds
                    lock(resource: 'docker-images-lock', inversePrecedence: true) {
                        services.each { svc ->
                            // ensure filePath & fileSuffix are present (set earlier or default)
                            def fPath = env.filePath ?: "target"
                            def fSuffix = env.fileSuffix ?: "jar"
                            def artifactFile = "${svc}-${env.version}.${fSuffix}"
                            docker.DockerBuildAndPushImage("${env.imageRegistry}", "${env.imageRegistryCredentialsId}",
                                    "${env.buName}", svc, "${env.version}", fPath, artifactFile)
                        }
                    }
                }
            }
        }

        // Helm release: CI (update values.yaml in k8s deployment repo, multiple services supported)
        stage("HelmReleaseFile_CI") {
            steps {
                script {
                    def k8sProjectId = gitlab.GetProjectId("${env.gitlabUserTokenCredentialsId}", "devops", "devops-k8s-deployment")
                    def fileName = "values.yaml"
                    def services = projectCustom.getServiceList("${env.serviceName}")
                    println("Updating Helm values for services: ${services}")

                    services.each { svc ->
                        def filePath = "${svc}%2f${fileName}"
                        def fileData = gitlab.GetRepositoryFile("${env.gitlabUserTokenCredentialsId}", k8sProjectId, filePath, env.helmBranchName)
                        def base64Content = kubernetes.HelmReleaseTemplateFileReplaceAndConvertToBase64(fileName, fileData, "${env.imageRegistry}/${env.buName}/${svc}", "${env.version}")
                        try {
                            gitlab.CreateRepositoryFile("${env.gitlabUserTokenCredentialsId}", k8sProjectId, env.helmBranchName, filePath, base64Content)
                        } catch (e) {
                            gitlab.UpdateRepositoryFile("${env.gitlabUserTokenCredentialsId}", k8sProjectId, env.helmBranchName, filePath, base64Content)
                        }
                    }
                }
            }
        }

        // Checkout helm repo for subsequent CD file editing
        stage("CompleteHelmReleaseFile") {
            when { environment name: 'skipCD', value: 'false' }
            steps {
                script {
                    println("Checkout helm repo for CD")
                    sh "[ -d ${env.namespace} ] || mkdir ${env.namespace}"
                    ws("${WORKSPACE}/${env.namespace}") {
                        checkout.GetCode("${env.helmSrcUrl}", "${env.helmBranchName}", "${env.gitUserPWDCredentialsId}")
                    }
                }
            }
        }

        // Helm release: CD (replace values.yaml in checked out helm repo)
        stage("HelmReleaseFile_CD") {
            when { environment name: 'skipCD', value: 'false' }
            steps {
                script {
                    def fileName = "values.yaml"
                    Map projectParamsMap = projectCustom.getProjectParamsMap("${params.envList}")
                    def services = projectCustom.getServiceList("${env.serviceName}")
                    println("Updating local Helm templates for services: ${services}")

                    services.each { svc ->
                        def filePath = "${env.namespace}/${svc}/${fileName}"
                        // some services might not need the same params: adjust inside projectCustom if needed
                        if ("${env.serviceName}" == "devops-web-be" && svc != "devops-gateway") {
                            kubernetes.HelmReleaseTemplateFileReplace(filePath, null, null, "${params.memory}", "${params.replicaCount}", projectParamsMap)
                        } else {
                            kubernetes.HelmReleaseTemplateFileReplace(filePath, env.domainName, env.accessDomainName, "${params.memory}", "${params.replicaCount}", projectParamsMap)
                        }
                        println("Updated Helm template for ${svc}")
                    }
                }
            }
        }

        // Helm deploy (loop multiple services if needed)
        stage("HelmDeploy") {
            when { environment name: 'skipCD', value: 'false' }
            steps {
                script {
                    def services = projectCustom.getServiceList("${env.serviceName}")
                    println("Deploying services: ${services}")

                    services.each { svc ->
                        kubernetes.HelmDeploy(env.namespace, "${env.namespace}/${svc}", svc)
                    }
                }
            }
        }

    } // stages

    post {
        success {
            script {
                notice.dingTalkPluginNotice("${env.dingTalkRebotIdCredentialsId}")
            }
        }
        failure {
            script {
                notice.dingTalkPluginNotice("${env.dingTalkRebotIdCredentialsId}")
            }
        }
        unstable {
            script {
                notice.dingTalkPluginNotice("${env.dingTalkRebotIdCredentialsId}")
            }
        }
    }

} // pipeline