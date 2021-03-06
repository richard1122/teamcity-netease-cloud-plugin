package com.hlyue.teamcity.agent.netease.web

import com.google.gson.Gson
import com.hlyue.teamcity.agent.netease.*
import com.hlyue.teamcity.agent.netease.Constants.Companion.buildLogger
import jetbrains.buildServer.BuildProject
import jetbrains.buildServer.controllers.*
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.agentPools.*
import jetbrains.buildServer.web.openapi.*
import kotlinx.coroutines.*
import org.springframework.stereotype.Component
import org.springframework.web.servlet.ModelAndView
import java.util.ArrayList
import javax.servlet.AsyncContext
import javax.servlet.http.*

@Component
class SettingsController(
  private val server: SBuildServer,
  private val pluginDescriptor: PluginDescriptor,
  private val manager: WebControllerManager,
  private val agentPoolManager: AgentPoolManager
): BaseController(server) {

  private companion object : Constants()

  private val myJspPath: String = pluginDescriptor.getPluginResourcesPath("settings.jsp")
  private val myHtmlPath: String = pluginDescriptor.getPluginResourcesPath("settings.html")
  private val gson = Gson()

  val myLogger = buildLogger()

  override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
    return if (isPost(request)) {
      doPost(request, response)
      null
    } else {
      doGet(request, response)
    }
  }

  fun doGet(request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
    val pools: MutableList<AgentPool> = ArrayList()
    val projectId: String = request.getParameter("projectId")
    if (BuildProject.ROOT_PROJECT_ID != projectId) {
      pools.add(AgentPoolUtil.DUMMY_PROJECT_POOL)
    }
    pools.addAll(agentPoolManager.getProjectOwnedAgentPools(projectId))
    val mv = ModelAndView(myJspPath)
    mv.model["basePath"] = myHtmlPath
    mv.model["agentPools"] = pools
    return mv
  }

  fun doPost(request: HttpServletRequest, response: HttpServletResponse) {
    request.setAttribute("org.apache.catalina.ASYNC_SUPPORTED", true)

    val post = request.inputStream.readBytes().toString(Charsets.UTF_8)
    val param = gson.fromJson(post, PostRequest::class.java)
    myLogger.info("post content: ${gson.toJson(param)}")
    val context = request.startAsync(request, response)
    val connector = NeteaseOpenApiConnector(param.accessKey, param.accessSecret)
    request(param, connector, context)
  }

  fun request(param: PostRequest, connector: NeteaseOpenApiConnector, context: AsyncContext) = GlobalScope.async {
    val res = when (param.resource) {
      "namespace" -> getNamespace(connector)
      "vpc" -> getVpc(connector)
      "subnet" -> getSubnet(connector, param.params["VpcId"] ?: "")
      "securityGroup" -> getSecurityGroup(connector, param.params["VpcId"] ?: "")
      "repositories" -> listRepos(connector)
      "repoTags" -> getImageTags(connector, param.params["RepositoryId"] ?: "")
      else -> null
    }
    context.response.contentType = "application/json"
    context.response.outputStream.print(res)
    context.complete()
  }

  suspend fun getNamespace(connector: NeteaseOpenApiConnector): String {
    return connector.NeteaseOpenApiRequestBuilder(
      action = "DescribeNamespaces",
      serviceName = "ncs",
      version = "2017-11-16"
    ).request()
  }

  suspend fun getVpc(connector: NeteaseOpenApiConnector): String {
    return connector.NeteaseOpenApiRequestBuilder(
      action = "ListVpc",
      serviceName = "vpc",
      version = "2017-11-30"
    ).request()
  }

  suspend fun getSubnet(connector: NeteaseOpenApiConnector, vpcId: String): String {
    return connector.NeteaseOpenApiRequestBuilder(
      action = "ListSubnet",
      version = "2017-11-30",
      serviceName = "vpc",
      query = mapOf(
        "VpcId" to vpcId
      )
    ).request()
  }

  suspend fun getSecurityGroup(connector: NeteaseOpenApiConnector, vpcId: String): String {
    return connector.NeteaseOpenApiRequestBuilder(
      action = "ListSecurityGroup",
      version = "2017-11-30",
      serviceName = "vpc",
      query = mapOf(
        "VpcId" to vpcId
      )
    ).request()
  }

  suspend fun listRepos(connector: NeteaseOpenApiConnector): String {
    return connector.NeteaseOpenApiRequestBuilder(
      action = "DescribeRepositories",
      version = "2018-03-08",
      serviceName = "ccr",
      query = mapOf(
        "Limit" to "100"
      )
    ).request()
  }

  suspend fun getImageTags(connector: NeteaseOpenApiConnector, repositoryId: String): String {
    return connector.NeteaseOpenApiRequestBuilder(
      action = "DescribeTags",
      version = "2018-03-08",
      serviceName = "ccr",
      query = mapOf(
        "RepositoryId" to repositoryId
      )
    ).request()
  }

  init {
    manager.registerController(myHtmlPath, this)
  }
}
