@file:Suppress("DEPRECATION")

package net.netrefined

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

class NetRedefined : JavaPlugin(), CommandExecutor {

    private lateinit var scope: CoroutineScope
    private val dnsProviders = listOf(
        "1.1.1.1" to "Cloudflare",
        "8.8.8.8" to "Google",
        "9.9.9.9" to "Quad9",
        "208.67.222.222" to "OpenDNS"
    )

    private val lastPingUse = ConcurrentHashMap<String, Long>()
    private val pingCooldownMs = 10_000L

    private val minecraftServerClass by lazy { Class.forName("net.minecraft.server.MinecraftServer") }
    private val getServerMethod by lazy { minecraftServerClass.getMethod("getServer") }
    private val connectionField by lazy {
        minecraftServerClass.getDeclaredField("ak").apply { trySetAccessibleCompat() }
    }
    private val networkManagersField by lazy {
        connectionField.type.getDeclaredField("h").apply { trySetAccessibleCompat() }
    }
    private val networkManagerChannelField by lazy {
        Class.forName("net.minecraft.network.NetworkManager").getDeclaredField("channel").apply { trySetAccessibleCompat() }
    }

    override fun onEnable() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        logger.info("NetRedefined enabled: initializing network optimizations")
        applySocketOptimizations()
        selectBestDNS()
        injectNettyChannelHandlers()
        getCommand("ping")?.apply {
            setExecutor(this@NetRedefined)
            tabCompleter = PingTabCompleter()
        }
    }

    override fun onDisable() {
        if (::scope.isInitialized && scope.coroutineContext[Job]?.isCancelled != true) {
            scope.cancel()
        }
        logger.info("NetRedefined disabled: resources cleaned up")
    }

    private fun applySocketOptimizations() {
        System.setProperty("io.netty.recycler.maxCapacity.default", "0")
        System.setProperty("io.netty.allocator.numDirectArenas", "0")
        System.setProperty("io.netty.noPreferDirect", "true")
        System.setProperty("networkaddress.cache.ttl", "60")
    }

    private fun selectBestDNS() {
        scope.launch {
            val results = dnsProviders.map { (ip, name) ->
                async {
                    val latency = measureLatency(ip)
                    Triple(name, ip, latency)
                }
            }.awaitAll()
                .filter { it.third > 0 }
                .sortedBy { it.third }

            results.firstOrNull()?.let { (name, ip, latency) ->
                System.setProperty("sun.net.spi.nameservice.nameservers", ip)
                System.setProperty("sun.net.spi.nameservice.provider.1", "dns,sun")
                logger.info("[NetRedefined] Best DNS: $name ($ip) with latency ${latency}ms")
            } ?: logger.warning("[NetRedefined] DNS benchmark failed; using default resolver")
        }
    }

    private suspend fun measureLatency(host: String): Long = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                val time = measureNanoTime {
                    socket.tcpNoDelay = true
                    socket.reuseAddress = true
                    socket.connect(InetSocketAddress(host, 53), 300)
                }
                TimeUnit.NANOSECONDS.toMillis(time)
            }
        } catch (_: Exception) {
            -1L
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectNettyChannelHandlers() {
        try {
            val minecraftServer = getServerMethod.invoke(null)
            val serverConnection = connectionField.get(minecraftServer)
            val networkManagers = networkManagersField.get(serverConnection) as? Iterable<*> ?: return

            for (networkManager in networkManagers) {
                val channel = networkManagerChannelField.get(networkManager) as? Channel ?: continue
                channel.config().apply {
                    isAutoRead = true
                    setOption(io.netty.channel.ChannelOption.TCP_NODELAY, true)
                    setOption(io.netty.channel.ChannelOption.SO_KEEPALIVE, true)
                    setOption(io.netty.channel.ChannelOption.SO_REUSEADDR, true)
                }
                if (channel.pipeline().get("netRedefinedThrottle") == null) {
                    channel.pipeline().addFirst("netRedefinedThrottle", object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            super.channelRead(ctx, msg)
                        }
                    })
                }
            }
            logger.info("NetRedefined: Successfully injected Netty handlers")
        } catch (e: Exception) {
            logger.warning("NetRedefined: Failed to inject Netty handlers: ${e.message}")
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /ping <player>", NamedTextColor.RED))
            return true
        }

        val playerUUID = (sender as? Player)?.uniqueId?.toString()
        if (playerUUID != null) {
            val now = System.currentTimeMillis()
            val lastUse = lastPingUse[playerUUID] ?: 0L
            val timeSinceLastUse = now - lastUse
            if (timeSinceLastUse < pingCooldownMs) {
                val secondsLeft = ((pingCooldownMs - timeSinceLastUse) / 1000).coerceAtLeast(1)
                sender.sendMessage(Component.text("Please wait $secondsLeft seconds before using /ping again.", NamedTextColor.RED))
                return true
            }
            lastPingUse[playerUUID] = now
        }

        val target = Bukkit.getPlayerExact(args[0])
        if (target?.isOnline == true) {
            val ping = getPlayerPing(target)
            sender.sendMessage(
                Component.text("${target.name}'s ping: ", NamedTextColor.GREEN)
                    .append(Component.text(if (ping >= 0) "$ping ms" else "Unavailable", NamedTextColor.YELLOW))
            )
        } else {
            sender.sendMessage(Component.text("Player not found or offline.", NamedTextColor.RED))
        }
        return true
    }

    private fun getPlayerPing(player: Player): Int {
        return try {
            val handle = player.javaClass.getMethod("getHandle").invoke(player)
            val pingField = handle.javaClass.getDeclaredField("e").apply { trySetAccessibleCompat() }
            pingField.getInt(handle)
        } catch (_: Exception) {
            -1
        }
    }

    private inner class PingTabCompleter : TabCompleter {
        override fun onTabComplete(
            sender: CommandSender,
            command: Command,
            alias: String,
            args: Array<out String>
        ): List<String> {
            return if (args.size == 1) {
                Bukkit.getOnlinePlayers()
                    .map { it.name }
                    .filter { it.startsWith(args[0], ignoreCase = true) }
            } else emptyList()
        }
    }

    // Robust helper for reflection accessibility (Java 8+ and Java 16+)
    private fun java.lang.reflect.AccessibleObject.trySetAccessibleCompat() {
        try {
            if (!this.isAccessible) {
                this.isAccessible = true
            }
        } catch (_: Exception) {
            try {
                val method = this.javaClass.getMethod("trySetAccessible")
                method.invoke(this)
            } catch (_: Exception) {
                // ignore, best effort
            }
        }
    }
}
