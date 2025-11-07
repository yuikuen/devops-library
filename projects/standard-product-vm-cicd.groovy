// 加载共享库
@Library("mylib@main") _

// 导入库
import org.devops.*

// New实例化
def checkout = new Checkout()
def build = new Build()
def codeScan = new CodeScan()
def notice = new Notice()
def gitlab = new GitLab()
def docker = new Docker()
def ansible = new Ansible()
def projectCustom = new ProjectCustom()

// 流水线
pipeline {
    agent { label "build" }

    options {
        skipDefaultCheckout true
        timestamps()
        buildDiscarder logRotator(daysToKeepStr: '180', numToKeepStr: '90')
        timeout(time: 1, unit: 'HOURS') // 设置全局超时时间
    }

    parameters {
        choice choices: [
                'git@gitserv.proaimltd.com.cn:proaimltd/devops-web-be.git',
                'git@gitserv.proaimltd.com.cn:proaimltd/devops-web-fe.git',
                'git@gitserv.proaimltd.com.cn:proaimltd/hnhf-bigscreen-fe.git',
        ], description: 'GitLab代码仓库地址', name: 'srcUrl'

        choice(name: 'refType', choices: ['branch', 'tags'], description: '请选择类型：branch=分支，tags=标签')
        string(name: 'refName', defaultValue: 'v2.8.0', description: '填写分支或标签名')

        activeChoice(
                choiceType: 'PT_SINGLE_SELECT',
                description: '选择目标主机',
                filterLength: 1,
                filterable: false,
                name: 'targetHost',
                script: groovyScript(
                        fallbackScript: [classpath: [], oldScript: '', sandbox: true, script: ''],
                        script: [classpath: [], oldScript: '', sandbox: true, script: '''
                return [
                    '192.168.100.127-项目A',
                    '192.168.100.128-项目B',
                ]
            ''']
                )
        )

        reactiveChoice(
                choiceType: 'PT_SINGLE_SELECT',
                description: '根据服务器动态显示环境',
                filterLength: 1,
                filterable: false,
                name: 'envList',
                referencedParameters: 'targetHost',
                script: groovyScript(
                        fallbackScript: [classpath: [], oldScript: '', sandbox: true, script: ''],
                        script: [classpath: [], oldScript: '', sandbox: true, script: '''
                    def envMap = [
                        '192.168.100.127-项目A': ['dev','test','pre'],
                        '192.168.100.128-项目B': ['test','prod'],
                    ]
    
                    if (!targetHost) {
                        return ['请先选择服务器']
                    }
    
                    // 支持多选：聚合所有选中主机的环境并去重
                    def servers = targetHost.tokenize(',')
                    def envs = servers.collect { envMap[it.trim()] ?: [] }.flatten().unique()
    
                    return envs ?: ['-- 未定义环境，请联系运维 --']
                ''']
                )
        )
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

    }

    stages {

        stage('Validate Parameters') {
            steps {
                script {
                    def envMap = [
                            '192.168.100.127-项目A': ['dev', 'test', 'pre'],
                            '192.168.100.128-项目B': ['test', 'prod'],
                    ]

                    def hosts = params.targetHost.split(",").collect { it.trim() }
                    def envSel = params.envList

                    // 校验第一个服务器是否支持当前环境
                    def firstHost = hosts[0]
                    if (!envMap.containsKey(firstHost)) {
                        error "❌ 服务器 ${firstHost} 未定义，请检查配置"
                    }
                    if (!envMap[firstHost].contains(envSel)) {
                        error "❌ 环境 ${envSel} 不属于服务器 ${firstHost}，允许环境: ${envMap[firstHost]}"
                    }

                    echo "✅ 参数校验通过：${hosts} -> ${envSel}"
                }
            }
        }

        stage('Clean Workspace') {
            steps {
                cleanWs()
            }
        }

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
                    // JOB任务前缀（业务名称/组名称）
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

                    // Git项目Id
                    env.projectId = projectCustom.getProjectIdByProjectName("${env.serviceName}")
                    if ("${env.projectId}" == "null") {
                        env.projectId = gitlab.GetProjectId("${env.gitlabUserTokenCredentialsId}", "${env.buName}", "${env.serviceName}")
                    }
                    println("serviceName：${env.serviceName}，projectId：${env.projectId}")

                    // Git提交ID
                    env.commitId = gitlab.GetShortCommitIdByEightDigit()
                    // Git提交超链接
                    env.commitWebURL = gitlab.GetCommitWebURLByApi("${env.gitlabUserTokenCredentialsId}", "${env.projectId}", "${params.refName}")
                    // 服务版本号（推荐定义："${refName}-${commitId}"）
                    env.version = "${params.refName}-${env.commitId}"

                    // 修改Jenkins构建描述
                    currentBuild.description = """ targetHost：${params.targetHost} \n serviceName：${env.serviceName} \n commitId：[${env.commitId}](${env.commitWebURL}) """
                    // 修改Jenkins构建名称
                    currentBuild.displayName = "${env.version}"
                }
            }
        }

        stage("Build") {
            steps {
                script {
                    println("Build")
                    projectCustom.executeBuildByServiceName("${env.serviceName}")
                    if ("${env.serviceName}" == "devops-web-fe") {
                        try {
                            println("Running CodeScan...")
                            nodejs('nodejs-18') {
                                codeScan.checkVueTscLint()
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
        }

        stage("DockerBuild") {
            steps {
                script {
                    def fileConfig = projectCustom.resolveFileConfig("${env.moduleType}")
                    env.filePath = fileConfig.filePath
                    env.fileSuffix = fileConfig.fileSuffix
                    env.newFileName = "${env.serviceName}-${env.version}.${env.fileSuffix}"

                    if (env.fileSuffix == "tar.gz") {
                        sh "cd ${env.filePath} && tar -zcvf ${env.newFileName} *"
                    }

                    def resourceLock = "docker-images-lock"
                    lock(resource: resourceLock, inversePrecedence: true) {
                        if ("${env.serviceName}" == "devops-web-be") {
                            if (params.refName.startsWith("v2.5.") || params.refName.startsWith("v2.3.")) {
                                // v2.5.x版本逻辑
                                def originalFileName = sh(returnStdout: true, script: """
                            find ${env.filePath} -maxdepth 1 -type f -name "*${env.fileSuffix}"
                        """).trim()
                                if (originalFileName) {
                                    sh "mv ${originalFileName} ${env.filePath}/${env.newFileName}"
                                } else {
                                    error "未找到匹配的文件！"
                                }
                                docker.DockerBuildAndPushImage("${env.imageRegistry}", "${env.imageRegistryCredentialsId}",
                                        "${env.buName}", "${env.serviceName}", "${env.version}", "${env.filePath}",
                                        "${env.newFileName}")
                            } else {
                                // 非v2.5版本逻辑（默认走v2.6、以后v2.7等也能兼容）
                                def services = ["gateway", "entrypoint", "auth", "sso-entrypoint"]
                                services.each { service ->
                                    def imageName = "${env.productName}-${service}"
                                    docker.DockerBuildAndPushImage("${env.imageRegistry}", "${env.imageRegistryCredentialsId}",
                                            "${env.buName}", "${imageName}", "${env.version}", "${env.filePath}",
                                            "${imageName}-0.1.0.${env.fileSuffix}")
                                }
                            }
                        } else {
                            // 其他服务直接打包推送
                            docker.DockerBuildAndPushImage("${env.imageRegistry}", "${env.imageRegistryCredentialsId}",
                                    "${env.buName}", "${env.serviceName}", "${env.version}", "${env.filePath}",
                                    "${env.newFileName}")
                        }
                    }
                }
            }
        }

        stage("AnsibleDeploy") {
            steps {
                script {
                    // 从选择的 targetHost 中提取出 IP 地址部分，值格式为 IP-项目名称
                    def targetHostList = params.targetHost.split(",").collect { it.split("-")[0] }

                    // 将纯净的 IP 地址列表传递给 Ansible
                    if ("${env.envList}" == "prod") {
                        ansible.deployUsingAnsibleScript(targetHostList.join(','), "/opt/docker-app/${env.serviceName}/deploy.sh", "${env.version}")
                    } else if ("${env.envList}" == "dev" || "${env.envList}" == "test" || "${env.envList}" == "pre") {
                        ansible.deployUsingAnsibleScript(targetHostList.join(','), "/opt/docker-app/${env.serviceName}-${env.envList}/deploy.sh", "${env.version}")
                    }
                }
            }
        }

    }

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

}
