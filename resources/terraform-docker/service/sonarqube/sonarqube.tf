/*
  default username：admin，default password：admin
  SonarQube使用了ES，需要修改一些内核参数：
  cp -p /etc/sysctl.conf /etc/sysctl.conf.bak$(date '+%Y%m%d%H%M%S')
  echo "vm.max_map_count = 262144" >> /etc/sysctl.conf && sysctl -w vm.max_map_count=262144
  插件链接：https://github.com/mc1arke/sonarqube-community-branch-plugin、
  https://github.com/xuhuisheng/sonar-l10n-zh、
  https://github.com/gabrie-allaigre/sonar-gitlab-plugin
  推荐安装汉化插件：[编程爱好者/sonar-l10n-zh](https://github.com/SonarQubeCommunity/sonar-l10n-zh/releases/latest)
  插件目录：/opt/sonarqube/extensions/plugins，通过Web界面安装汉化插件：Administration|Marketplace|Plugins|Chinese Pack
*/
# Pull image
resource "docker_image" "sonarqube" {
  # (String) The name of the Docker image, including any tags or SHA256 repo digests.
  name         = "sonarqube:9.9-community"
  # (Boolean) If true, then the Docker image won't be deleted on destroy operation.
  # If this is false, it will delete the image from the docker local storage on destroy operation.
  keep_locally = true
}

locals {
  container_name         = "sonarqube"
  container_image        = docker_image.sonarqube.name
  container_memory       = 4096
  container_memory_swap  = 15360
  # stop_timeout (Number) Timeout (in seconds) to stop a container.
  container_stop_timeout = 3600
  container_env          = [
    "TZ=Asia/Shanghai",
    "NO_PROXY=localhost,127.0.0.1,127.0.0.0/8,10.0.0.0/8,172.0.0.0/8,192.168.0.0/16",
    "HTTP_PROXY=http://192.168.50.126:7890",
    "HTTPS_PROXY=http://192.168.50.126:7890",
    "ALL_PROXY=socks://192.168.50.126:7890",
    "SONAR_WEB_CONTEXT=/sonarqube",
    # 注意：同步postgresql容器IP：172.18.0.5
    "SONAR_JDBC_URL=jdbc:postgresql://172.18.0.5:5432/sonar",
    "SONAR_JDBC_USERNAME=postgres",
    "SONAR_JDBC_PASSWORD=proaim@2013"
  ]
  container_network = data.terraform_remote_state.network.outputs.network[0]["name"]
  container_ip      = "172.18.0.6"
  container_ports   = [
    {
      internal = 9000
      external = 9000
    }
  ]
  container_volumes = [
    {
      host_path      = "/etc/localtime"
      container_path = "/etc/localtime"
    },
    {
      host_path      = "/data/sonarqube/conf"
      container_path = "/opt/sonarqube/conf"
    },
    {
      host_path      = "/data/sonarqube/data"
      container_path = "/opt/sonarqube/data"
    },
    {
      host_path      = "/data/sonarqube/extensions"
      container_path = "/opt/sonarqube/extensions"
    },
    {
      host_path      = "/data/sonarqube/logs"
      container_path = "/opt/sonarqube/logs"
    }
  ]
}

# Start a container
resource "docker_container" "sonarqube" {
  name            = local.container_name
  image           = local.container_image
  memory          = local.container_memory
  memory_swap     = local.container_memory_swap
  env             = local.container_env
  stop_timeout    = local.container_stop_timeout
  restart         = "always"
  networks_advanced {
    name         = local.container_network
    ipv4_address = local.container_ip
  }

  dynamic "ports" {
    for_each = local.container_ports
    content {
      internal = ports.value.internal
      external = ports.value.external
      ip       = "0.0.0.0"
      protocol = "tcp"
    }
  }

  dynamic "volumes" {
    for_each = local.container_volumes
    content {
      host_path      = volumes.value.host_path
      container_path = volumes.value.container_path
    }
  }

}