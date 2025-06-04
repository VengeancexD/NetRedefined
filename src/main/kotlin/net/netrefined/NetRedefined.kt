@file:Suppress("DEPRECATION", "UNCHECKED_CAST")

package net.redefined

import com.comphenix.protocol.*
import com.comphenix.protocol.events.*
import io.netty.channel.*
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.*
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.Field
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class NetRedefined : JavaPlugin(), CommandExecutor, TabCompleter {
    private val protocolManager = ProtocolLibrary.getProtocolManager()
    private val packetQueues = ConcurrentHashMap<UUID, ConcurrentLinkedQueue<PacketEvent>>()
    private val packetCounts = ConcurrentHashMap<UUID, AtomicInteger>()
    private val pingFields = ConcurrentHashMap<Class<*>, Field>()

    override fun onEnable() {
        protocolManager.addPacketListener(object : PacketAdapter(this, ListenerPriority.HIGHEST,
            PacketType.Play.Client.FLYING,
            PacketType.Play.Client.LOOK,
            PacketType.Play.Client.POSITION,
            PacketType.Play.Client.POSITION_LOOK) {
            override fun onPacketReceiving(event: PacketEvent) {
                val player = event.player
                val uuid = player.uniqueId
                packetQueues.computeIfAbsent(uuid) { ConcurrentLinkedQueue() }.add(event)
                packetCounts.computeIfAbsent(uuid) { AtomicInteger() }.incrementAndGet()
                if (packetCounts[uuid]?.get() ?: 0 > 50) {
                    event.isCancelled = true
                }
            }
        })

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            packetQueues.forEach { (uuid, queue) ->
                val player = Bukkit.getPlayer(uuid) ?: return@forEach
                while (queue.isNotEmpty()) {
                    val event = queue.poll() ?: continue
                    protocolManager.receiveClientPacket(player, event.packet)
                }
                packetCounts[uuid]?.set(0)
            }
        }, 1L, 1L)

        Bukkit.getPluginManager().registerEvents(PlayerConnectionHandler(), this)
        getCommand("ping")?.setExecutor(this)
        getCommand("ping")?.tabCompleter = this
    }

    private fun injectChannel(player: Player) {
        try {
            val handle = player.javaClass.getMethod("getHandle").invoke(player)
            val connectionField = handle.javaClass.getDeclaredField("b").apply { isAccessible = true }
            val connection = connectionField.get(handle)
            val channelField = connection.javaClass.getDeclaredField("channel").apply { isAccessible = true }
            val channel = channelField.get(connection) as Channel

            channel.config().apply {
                setOption(ChannelOption.TCP_NODELAY, true)
                setOption(ChannelOption.SO_KEEPALIVE, true)
                setOption(ChannelOption.SO_REUSEADDR, true)
                setOption(ChannelOption.SO_SNDBUF, 524288)
                setOption(ChannelOption.SO_RCVBUF, 524288)
            }

            if (!channel.pipeline().names().contains("netredefined_handler")) {
                channel.pipeline().addBefore("packet_handler", "netredefined_handler", object : ChannelInboundHandlerAdapter() {
                    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                        ctx.fireChannelRead(msg)
                    }
                })
            }
        } catch (_: Throwable) {}
    }

    inner class PlayerConnectionHandler : org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler
        fun onJoin(e: org.bukkit.event.player.PlayerJoinEvent) {
            injectChannel(e.player)
        }

        @org.bukkit.event.EventHandler
        fun onQuit(e: org.bukkit.event.player.PlayerQuitEvent) {
            val uuid = e.player.uniqueId
            packetQueues.remove(uuid)
            packetCounts.remove(uuid)
        }
    }

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        if (cmd.name.equals("ping", true)) {
            if (args.isEmpty()) {
                sender.sendMessage(Component.text("Usage: /ping <player>"))
                return true
            }
            val target = Bukkit.getPlayerExact(args[0])
            if (target != null && target.isOnline) {
                val ping = getPing(target)
                if (ping >= 0) sender.sendMessage(Component.text("${target.name}'s ping: $ping ms"))
            } else {
                sender.sendMessage(Component.text("Player not found or not online"))
            }
        }
        return true
    }

    private fun getPing(player: Player): Int {
        return try {
            val handle = player.javaClass.getMethod("getHandle").invoke(player)
            val pingField = pingFields.computeIfAbsent(handle.javaClass) {
                it.getDeclaredField("e").apply { isAccessible = true }
            }
            pingField.getInt(handle)
        } catch (_: Throwable) { -1 }
    }

    override fun onTabComplete(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): List<String>? {
        return if (cmd.name.equals("ping", true) && args.size == 1) {
            Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        } else null
    }
}
