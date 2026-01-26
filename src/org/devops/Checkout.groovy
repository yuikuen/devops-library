package org.devops

/**
 * 下载代码
 * @param srcUrl 代码地址
 * @param refName 分支名称
 * @param credentialsId 凭据Id
 * @return
 */
def GetCode(srcUrl, refName, credentialsId) {

    checkout([
            $class           : 'GitSCM',
            branches         : [[name: refName]],
            extensions       : [
                    [$class : 'CloneOption',
                     depth  : 1, // 启用浅克隆
                     shallow: true, // 设置为 true 开启浅克隆
                     timeout: 10
                    ],
                    [$class             : 'SubmoduleOption',
                     disableSubmodules  : true,
                     parentCredentials  : false,
                     recursiveSubmodules: true, // 设置为true以递归检出子模块
                     trackingSubmodules : false // 设置为true以跟踪子模块提交
                    ],
                    [$class     : 'LocalBranch',
                     localBranch: refName // 指定本地分支或标签名称，与远程同名
                    ],
            ],
            userRemoteConfigs: [[url: srcUrl, credentialsId: credentialsId]]
    ])
}

/**
 * 下载代码
 * @param srcUrl 代码地址
 * @param refName 分支名称
 * @param refType 分支类型
 * @param credentialsId 凭据Id
 * @return
 */
def GetCode(srcUrl, refName, refType, credentialsId) {
    // 处理 Ref 名称，兼容 tag 和 branch
    def fullRef = "refs/${refType == 'tags' ? 'tags' : 'heads'}/${refName}"

    checkout([
            $class           : 'GitSCM',
            branches         : [[name: fullRef]],
            extensions       : [
                    // 构建前清理工作区，解决 dirty workspace 导致的 submodule 配置残留问题
                    [$class: 'CleanBeforeCheckout'],
                    [$class : 'CloneOption',
                     depth  : 1, // 启用浅克隆
                     shallow: true, // 设置为 true 开启浅克隆
                     timeout: 10
                    ],
                    // 设置检出超时时间（单位：分钟），防止网络慢导致任务卡死
                    [$class: 'CheckoutOption', timeout: 20],
                    [
                            $class             : 'SubmoduleOption',
                            disableSubmodules  : true,
                            parentCredentials  : true, // 确保子模块能用主账号权限
                            recursiveSubmodules: true, // 设置为true以递归检出子模块
                            trackingSubmodules : false, // 设置为true以跟踪子模块提交
                            timeout            : 20
                    ],
                    [$class: 'LocalBranch', localBranch: refName],
            ],
            userRemoteConfigs: [[url: srcUrl, credentialsId: credentialsId]]
    ])
}

/**
 * 浅克隆代码
 * @param srcUrl 代码地址
 * @param branchName 分支名称
 * @param credentialsId 凭据Id
 * @return
 */
def GetCodeShallowClone(srcUrl, branchName, credentialsId) {
    checkout([
            $class                           : 'GitSCM',
            branches                         : [[name: "*/${branchName}"]],
            doGenerateSubmoduleConfigurations: false,
            extensions                       : [
                    [$class: 'CloneOption', depth: 10, noTags: true, shallow: true],  // 只拉取最近 1 个 commit
                    [$class: 'LocalBranch', localBranch: "${branchName}"]
            ],
            userRemoteConfigs                : [[url: srcUrl, credentialsId: credentialsId]]
    ])
}

/**
 * 增量拉取代码
 * @param srcUrl 代码地址
 * @param branchName 分支名称
 * @param credentialsId 凭据Id
 * @param targetBranch 目标分支
 * @return
 */
def GetCodeIncremental(srcUrl, branchName, credentialsId, targetBranch = 'main') {
    checkout([
            $class           : 'GitSCM',
            branches         : [[name: branchName]],
            extensions       : [
                    [$class: 'LocalBranch', localBranch: branchName],
                    [$class: 'CloneOption', depth: 1, noTags: true, shallow: true],
                    [$class: 'PreBuildMerge', options: [
                            mergeRemote: 'origin',
                            mergeTarget: targetBranch  // 只拉取当前分支相对于 targetBranch 的变更
                    ]]
            ],
            userRemoteConfigs: [[url: srcUrl, credentialsId: credentialsId]]
    ])
}