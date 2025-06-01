// NetRedefined.kt (Fixed for ProtocolLib 5.3.0+ and Paper 1.20+/1.21)
@file:Suppress("DEPRECATION", "UNCHECKED_CAST")

package net.netrefined

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.*
import io.netty.channel.Channel
import io.netty.channel.ChannelOption
import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.*
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

class NetRedefined : JavaPlugin(), CommandExecutor {

    private val dnsProviders = listOf(
        "1.1.1.1" to "Cloudflare",
        "8.8.8.8" to "Google",
        "9.9.9.9" to "Quad9",
        "208.67.222.222" to "OpenDNS",
        "94.140.14.14" to "AdGuard"
    )
    private val lastPingUse = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onEnable() {
        optimizeTCPStack()
        applyNettyTuning()
        setupProtocolInterceptors()
        benchmarkAndSetDNS()
        setupPingCommand()
        logger.info("NetRedefined fully loaded with advanced networking enhancements.")
    }

    override fun onDisable() {
        scope.cancel()
        logger.info("NetRedefined disabled and cleaned up.")
    }

    private fun optimizeTCPStack() {
        System.setProperty("networkaddress.cache.ttl", "60")
        System.setProperty("sun.net.client.defaultConnectTimeout", "400")
        System.setProperty("sun.net.client.defaultReadTimeout", "400")
        logger.info("[NetRedefined] TCP/IP stack tuning applied.")
    }

    private fun applyNettyTuning() {
        System.setProperty("io.netty.recycler.maxCapacity.default", "0")
        System.setProperty("io.netty.allocator.numDirectArenas", "0")
        System.setProperty("io.netty.noPreferDirect", "true")
        logger.info("[NetRedefined] Netty channel tuning applied.")
    }

    private fun setupProtocolInterceptors() {
        ProtocolLibrary.getProtocolManager().addPacketListener(object : PacketAdapter(
            this, ListenerPriority.HIGHEST,
            PacketType.Play.Client.FLYING,
            PacketType.Play.Client.LOOK,
            PacketType.Play.Client.POSITION_LOOK
        ) {
            override fun onPacketReceiving(event: PacketEvent) {
                val channel = getPlayerChannel(event.player)
                channel?.let {
                    try {
                        it.config().apply {
                            isAutoRead = true
                            setOption(ChannelOption.TCP_NODELAY, true)
                            setOption(ChannelOption.SO_KEEPALIVE, true)
                            setOption(ChannelOption.SO_REUSEADDR, true)
                            setOption(ChannelOption.IP_TOS, 0x10)
                        }
                    } catch (_: Exception) {}
                }
            }
        })
        logger.info("[NetRedefined] ProtocolLib interceptors active.")
    }

    private fun getPlayerChannel(player: Player): Channel? {
        return try {
            val handle = player.javaClass.getMethod("getHandle").invoke(player)
            val connectionField = handle.javaClass.getDeclaredField("b").apply { isAccessible = true }
            val connection = connectionField.get(handle)
            val networkManagerField = connection.javaClass.getDeclaredField("h").apply { isAccessible = true }
            val networkManager = networkManagerField.get(connection)
            val channelField = networkManager.javaClass.getDeclaredField("channel").apply { isAccessible = true }
            channelField.get(networkManager) as? Channel
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun benchmarkAndSetDNS() = scope.launch {
        val best = dnsProviders.map { (ip, name) ->
            async {
                val time = measurePing(ip)
                Triple(name, ip, time)
            }
        }.awaitAll().filter { it.third > 0 }.minByOrNull { it.third }

        best?.let { (name, ip, time) ->
            System.setProperty("sun.net.spi.nameservice.nameservers", ip)
            System.setProperty("sun.net.spi.nameservice.provider.1", "dns,sun")
            logger.info("[NetRedefined] Optimal DNS: $name ($ip) with ${time}ms latency.")
        } ?: logger.warning("[NetRedefined] DNS benchmark failed.")
    }

    private suspend fun measurePing(host: String): Long = withContext(Dispatchers.IO) {
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
            -1
        }
    }

    private fun setupPingCommand() {
        getCommand("ping")?.apply {
            setExecutor(this@NetRedefined)
            tabCompleter = TabCompleter { _, _, _, args ->
                if (args.size == 1) Bukkit.getOnlinePlayers().map { it.name }
                    .filter { it.startsWith(args[0], ignoreCase = true) } else emptyList()
            }
        }
    }

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /ping <player>", NamedTextColor.RED))
            return true
        }

        val uuid = (sender as? Player)?.uniqueId?.toString()
        if (uuid != null && System.currentTimeMillis() - (lastPingUse[uuid] ?: 0L) < 10_000L) {
            sender.sendMessage(Component.text("Wait before retrying ping.", NamedTextColor.RED))
            return true
        }

        lastPingUse[uuid ?: ""] = System.currentTimeMillis()

        val target = Bukkit.getPlayerExact(args[0])
        val ping = getPing(target)
        sender.sendMessage(
            Component.text("${target?.name ?: "Unknown"} ping: ", NamedTextColor.GREEN)
                .append(Component.text(if (ping >= 0) "$ping ms" else "Unavailable", NamedTextColor.YELLOW))
        )
        return true
    }

    private fun getPing(player: Player?): Int {
        return try {
            val handle = player?.javaClass?.getMethod("getHandle")?.invoke(player)
            val field = handle?.javaClass?.getDeclaredField("e")?.apply { isAccessible = true }
            field?.getInt(handle) ?: -1
        } catch (_: Exception) {
            -1
        }
    }
}
