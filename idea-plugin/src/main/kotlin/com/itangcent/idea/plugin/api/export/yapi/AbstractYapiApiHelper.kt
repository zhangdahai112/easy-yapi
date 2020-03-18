package com.itangcent.idea.plugin.api.export.yapi

import com.google.gson.JsonElement
import com.google.inject.Inject
import com.itangcent.common.logger.traceError
import com.itangcent.common.utils.GsonUtils
import com.itangcent.idea.plugin.api.export.ReservedResponseHandle
import com.itangcent.idea.plugin.api.export.StringResponseHandler
import com.itangcent.idea.plugin.api.export.reserved
import com.itangcent.idea.plugin.settings.SettingBinder
import com.itangcent.intellij.config.ConfigReader
import com.itangcent.intellij.logger.Logger
import com.itangcent.suv.http.HttpClientProvider
import org.apache.commons.lang3.StringUtils
import java.io.ByteArrayOutputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.*
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.collections.HashMap
import kotlin.concurrent.withLock

abstract class AbstractYapiApiHelper : YapiApiHelper {
    @Inject
    private val settingBinder: SettingBinder? = null

    @Inject
    protected val logger: Logger? = null

    @Inject
    private val configReader: ConfigReader? = null

    @Inject
    protected val httpClientProvide: HttpClientProvider? = null

    @Volatile
    var server: String? = null

    private var projectIdCache: HashMap<String, String> = HashMap()//token->id

    var projectInfoCache: HashMap<String, JsonElement> = HashMap()//id->info

    protected var cacheLock: ReadWriteLock = ReentrantReadWriteLock()

    open fun hasPrivateToken(module: String): Boolean {
        return getPrivateToken(module) != null
    }

    override fun findServer(): String? {
        if (!server.isNullOrBlank()) return server
        server = configReader!!.first("server")?.trim()?.removeSuffix("/")
        if (!server.isNullOrBlank()) return server
        server = settingBinder!!.read().yapiServer?.trim()?.removeSuffix("/")
        return server
    }

    override fun setYapiServer(yapiServer: String) {
        val settings = settingBinder!!.read()
        settings.yapiServer = yapiServer
        settingBinder.save(settings)
        Thread.sleep(200)
        server = yapiServer.removeSuffix("/")
    }

    open fun getProjectWeb(module: String): String? {
        val token = getPrivateToken(module)
        val projectId = getProjectIdByToken(token!!) ?: return null
        return "$server/project/$projectId/interface/api"
    }

    open protected fun findErrorMsg(res: String?): String? {
        if (res == null) return "no response"
        if (StringUtils.isNotBlank(res) && res.contains("errmsg")) {
            val returnObj = GsonUtils.parseToJsonTree(res)
            val errMsg = returnObj
                    ?.asJsonObject
                    ?.get("errmsg")
                    ?.asString
            if (StringUtils.isNotBlank(errMsg) && !errMsg!!.contains("成功")) {
                return errMsg
            }
        }
        return null
    }

    override fun getProjectIdByToken(token: String): String? {
        var projectId = cacheLock.readLock().withLock { projectIdCache[token] }
        if (projectId != null) return projectId
        try {
            projectId = getProjectInfo(token, null)?.asJsonObject
                    ?.get("data")
                    ?.asJsonObject
                    ?.get("_id")
                    ?.asString
        } catch (e: IllegalStateException) {
            logger!!.error("invalid token:$token")
        }
        if (projectId != null) {
            cacheLock.writeLock().withLock {
                projectIdCache[token] = projectId
            }
        }
        return projectId
    }

    override fun getProjectInfo(token: String, projectId: String?): JsonElement? {
        if (projectId != null) {
            val cachedProjectInfo = cacheLock.readLock().withLock { projectInfoCache[projectId] }
            if (cachedProjectInfo != null) return cachedProjectInfo
        }

        var url = "$server$GETPROJECT?token=$token"
        if (projectId != null) {
            url = "$url&id=$projectId"
        }

        val ret = getByApi(url, false) ?: return null
        var projectInfo: JsonElement? = null
        try {
            projectInfo = GsonUtils.parseToJsonTree(ret)
        } catch (e: Exception) {
            logger!!.error("error to parse project [$projectId] info:$ret")
        }
        if (projectId != null && projectInfo != null) {
            cacheLock.writeLock().withLock { projectInfoCache[projectId] = projectInfo }
        }
        return projectInfo
    }

    open fun getByApi(url: String, dumb: Boolean = true): String? {
        return try {
            httpClientProvide!!.getHttpClient()
                    .get(url)
                    .call()
                    .string()
        } catch (e: SocketTimeoutException) {
            if (!dumb) {
                logger!!.trace("$url connect timeout")
                throw e
            }
            logger!!.error("$url connect timeout")
            null
        } catch (e: SocketException) {
            if (!dumb) {
                logger!!.trace("$url is unreachable (connect failed)")
                throw e
            }
            logger!!.error("$url is unreachable (connect failed)")
            null
        } catch (e: Exception) {
            if (!dumb) {
                logger!!.traceError("request $url failed", e)
                throw e
            }
            logger!!.traceError("request $url failed", e)
            null
        }
    }

    protected fun reservedResponseHandle(): ReservedResponseHandle<String> {
        return StringResponseHandler.DEFAULT_RESPONSE_HANDLER.reserved()
    }

    private var tokenMap: HashMap<String, String>? = null

    override fun getPrivateToken(module: String): String? {

        cacheLock.readLock().withLock {
            if (tokenMap != null) {
                return tokenMap!![module]
            }
        }

        cacheLock.writeLock().withLock {
            if (tokenMap == null) {
                initToken()
            }
            return tokenMap!![module]
        }
    }

    private fun initToken() {
        tokenMap = HashMap()
        val settings = settingBinder!!.read()
        if (settings.yapiTokens != null) {
            val properties = Properties()
            properties.load(settings.yapiTokens!!.byteInputStream())
            properties.forEach { t, u -> tokenMap!![t.toString()] = u.toString() }
        }
    }

    private fun updateTokens(handle: (Properties) -> Unit) {

        cacheLock.writeLock().withLock {
            val settings = settingBinder!!.read()
            val properties = Properties()
            if (settings.yapiTokens != null) {
                properties.load(settings.yapiTokens!!.byteInputStream())
            }
            handle(properties)

            val byteOutputStream = ByteArrayOutputStream()
            properties.store(byteOutputStream, "")
            settings.yapiTokens = byteOutputStream.toString()
            settingBinder.save(settings)
            if (tokenMap == null) {
                tokenMap = HashMap()
            } else {
                tokenMap!!.clear()
            }
            properties.forEach { t, u -> tokenMap!![t.toString()] = u.toString() }
        }
    }

    override fun setToken(module: String, token: String) {
        updateTokens { properties ->
            properties[module] = token
        }
    }

    override fun removeTokenByModule(module: String) {
        updateTokens { properties ->
            properties.remove(module)
        }
    }

    override fun removeToken(token: String) {

        updateTokens { properties ->
            val removedKeys = properties.entries
                    .filter { it.value == token }
                    .map { it.key }
                    .toList()
            removedKeys.forEach { properties.remove(it) }
        }
    }

    override fun readTokens(): HashMap<String, String> {
        if (tokenMap == null) {
            initToken()
        }
        return tokenMap!!
    }

    companion object {
        var GETPROJECT = "/api/project/get"
    }
}