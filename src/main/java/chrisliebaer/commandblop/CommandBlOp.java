package chrisliebaer.commandblop;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import de.tr7zw.nbtapi.NBTTileEntity;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class CommandBlOp extends JavaPlugin implements Listener, CommandExecutor {
	
	private ProtocolManager protocolManager;
	private FakeOpInterceptor opInterceptor;

	private static final Map<String, String> commandPermissions = new HashMap<>();

	static {
		commandPermissions.put("tp", "minecraft.command.teleport");
	}
	
	@Override
	public void onEnable() {
		getServer().getPluginManager().registerEvents(this, this);
		
		// register op interceptor for fake op
		protocolManager = ProtocolLibrary.getProtocolManager();
		opInterceptor = new FakeOpInterceptor(protocolManager, this);
		protocolManager.addPacketListener(opInterceptor);
		
		// register on incoming command block updates
		protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Client.SET_COMMAND_BLOCK) {
			@Override
			public void onPacketReceiving(PacketEvent event) {
				onSetCommandPacket(event);
			}
		});
		
		// клиент может сбросить оп-статус в любой момент (respawn, смена измерения и т.п.),
		// поэтому периодически переотправляем фейк-оп всем, у кого есть право
		getServer().getScheduler().runTaskTimer(this, () -> {
			for (Player player : getServer().getOnlinePlayers()) {
				opInterceptor.fakeOp(player);
			}
		}, 2400L, 2400L); // каждые 2 минуты
	}
	
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player)) {
			sender.sendMessage("§cКоманда доступна только игрокам.");
			return true;
		}
		
		// мгновенно переотправляет фейк-оп клиенту, если тот его потерял (полезно после входа/смены измерения)
		opInterceptor.fakeOp((Player) sender);
		sender.sendMessage("§aСтатус оператора отправлен. Если командные блоки всё ещё недоступны — проверь права commandblop.fakeop и commandblop.*");
		return true;
	}
	
	private boolean ignore(PacketEvent event) {
		var id = event.getPacketType().getCurrentId();
		return id == 16 || id == 74 || id == 33 || id == 14 || id == 18;
	}
	
	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent ev) {
		Player player = ev.getPlayer();
		
		// права могут подгружаться асинхронно после входа, а пакет может потеряться,
		// поэтому отправляем оп-статус несколько раз с задержкой
		opInterceptor.fakeOp(player);
		for (long delay : new long[]{20L, 60L, 120L, 240L}) {
			getServer().getScheduler().runTaskLater(this, () -> {
				if (player.isOnline())
					opInterceptor.fakeOp(player);
			}, delay);
		}
	}

	@EventHandler
	public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
		Player player = event.getPlayer();
		if (player.isOp()) {
			return;
		}
		String[] parts = event.getMessage().split(" ");
		for (String arg : parts) {
			if (arg.startsWith("@") && !arg.equalsIgnoreCase("@p")) {
				player.sendMessage("§cИспользование селектора " + arg + " запрещено в чат-командах.");
				event.setCancelled(true);
				return;
			}
		}
	}


	private void onSetCommandPacket(PacketEvent ev) {
		var player = ev.getPlayer();
		if (player.isOp() || !Permissions.has(player, Permissions.EDIT))
			return;
		
		// cancel event since we are going to handle it ourself
		ev.setCancelled(true);
		
		var container = ev.getPacket();
		
		Location loc = container.getBlockPositionModifier().read(0).toLocation(player.getWorld());
		String command = container.getStrings().read(0);
		boolean trackOutput = container.getBooleans().read(0);
		boolean conditional = container.getBooleans().read(1);
		boolean automatic = container.getBooleans().read(2);

		String[] commandParts = command.split(" ");
		String baseCommand = commandParts[0].toLowerCase();

		if (baseCommand.startsWith("/")) {
			baseCommand = baseCommand.substring(1);
		}

		for (String part : commandParts) {
			if (part.equalsIgnoreCase("particle")) {
				String last = commandParts[commandParts.length - 1];
				try {
					int count = Integer.parseInt(last);
					if (count > 200) {
						player.sendMessage("§cМаксимальное количество частиц — 200.");
						return;
					}
				} catch (NumberFormatException ignore) {
				}
				break;
			}
		}

		// опциональная проверка: не-опы не имеют прав minecraft.command.* (ванила выдаёт их только операторам),
		// поэтому по умолчанию выключена; включить: restrict-commands: true в config.yml
		if (getConfig().getBoolean("restrict-commands", false)) {
			if (!player.hasPermission("minecraft.command." + command.toLowerCase())) {
				String associatedPermission = commandPermissions.get(baseCommand);
				if (!player.hasPermission("minecraft.command." + baseCommand.toLowerCase()) &&
						(associatedPermission == null || !player.hasPermission(associatedPermission))) {
					player.sendMessage("У тебя нет прав на использование этой команды в командном блоке.");
					return;
				}
			}
		}

		for (String argument : commandParts) {
			if (argument.startsWith("@") && !argument.equalsIgnoreCase("@p")) {
				player.sendMessage("Использование других аргументов с символом @, кроме @p в командном блоке запрещено.");
				return;
			}
		}
		
		// cause fuck you, that's why
		String mode = CommandBlockMode.fromPacketHandle(container.getHandle());
		//log.info("updating cmdblock at {}, command: {}, mode: {}, track: {}, conditional: {}, automatic: {}",
				//loc, command, mode, trackOutput, conditional, automatic);
		
		// move update to main thread
		getServer().getScheduler().runTask(this, () -> {
			var block = loc.getBlock();
			
			if (!(block.getState() instanceof CommandBlock)) {
				log.warn("{} attempted to change non-existing command block at: {}", player, loc);
				return;
			}
			
			// сохраняем имя блока (пакет его не содержит) и направление
			String customName = ((CommandBlock) block.getState()).getName();
			BlockFace facing = ((Directional) block.getBlockData()).getFacing();
			
			// update block according to new type
			block.setType(CommandBlockMode.toMaterial(mode));
			
			// команду и имя применяем через Bukkit API
			CommandBlock state = (CommandBlock) block.getState();
			state.setCommand(command);
			state.setName(customName);
			state.update();
			
			// TrackOutput и auto не имеют Bukkit API — пишем через NBT
			var nbt = new NBTTileEntity(block.getState());
			nbt.setByte("TrackOutput", (byte) (trackOutput ? 1 : 0));
			nbt.setByte("auto", (byte) (automatic ? 1 : 0));
			
			// adjust facing (also props for making your own fucking class names collide *slow clap*)
			var blockData = block.getBlockData();
			Directional directionalData = (Directional) blockData;
			org.bukkit.block.data.type.CommandBlock commandBlockData = (org.bukkit.block.data.type.CommandBlock) blockData;
			
			commandBlockData.setConditional(conditional);
			directionalData.setFacing(facing);
			block.setBlockData(blockData);
				
			// клиент открывает GUI командного блока сам (по предсказанию хода, т.к. у него fake-op),
			// читая свою локальную копию блок-сущности. CraftBlockState.update() не шлёт пакет
			// TILE_ENTITY_DATA, поэтому без рассылки клиент остаётся со старой (пустой) командой,
			// и при повторном открытии GUI команда не видна, хотя на сервере она сохранена.
			broadcastCommandBlockTileData(block);
		});
	}
	
	@EventHandler
	public void onPlayerInteract(PlayerInteractEvent ev) {
		var player = ev.getPlayer();
		var block = ev.getClickedBlock();
		if (block == null || player.isOp() || player.getGameMode() != GameMode.CREATIVE)
			return;
		
		var type = block.getType();
		if (type == Material.COMMAND_BLOCK ||
				type == Material.CHAIN_COMMAND_BLOCK ||
				type == Material.REPEATING_COMMAND_BLOCK) {
			
			if (ev.getAction() == Action.LEFT_CLICK_BLOCK && Permissions.has(player, Permissions.BREAK)) {
				if (!canPlayerModifyRegion(player, block.getLocation())) {
					player.sendMessage("Здесь нельзя ломать командный блок.");
					ev.setCancelled(true);
					return;
				}
				block.breakNaturally();
				return;
			}
			
			// sneaking allows to place blocks without activating command block, this is vanilla behavior
			if (!Permissions.has(player, Permissions.VIEW) || player.isSneaking()) {
				boolean placed = handleCommandBlockPlace(ev);
				if (!placed && !Permissions.has(player, Permissions.VIEW)) {
					// мы выдаём ванильное право minecraft.commandblock, иначе серверный обработчик
					// (CommandBlock.use) сам открыл бы GUI игроку без права на просмотр
					ev.setCancelled(true);
				}
				return;
			}
			
			// since canceling the event will also close the command block gui, we remove the item from the hand
			var inventory = player.getInventory();
			var stack = new ItemStack(inventory.getItemInMainHand());
			inventory.getItemInMainHand().setAmount(0);
			getServer().getScheduler().runTask(this, () -> player.getInventory().setItemInMainHand(stack));
			
			// send command block tile data to client for display
			CommandBlock cmdBlock = (CommandBlock) block.getState();
			sendCommandBlockTileData(player, cmdBlock);
		}
		
		if (handleCommandBlockPlace(ev)) {
			ev.setCancelled(true);
		}
	}
	
	private boolean handleCommandBlockPlace(PlayerInteractEvent ev) {
		var type = ev.getMaterial();
		var player = ev.getPlayer();
		var world = player.getWorld();
		if (type == Material.COMMAND_BLOCK ||
				type == Material.CHAIN_COMMAND_BLOCK ||
				type == Material.REPEATING_COMMAND_BLOCK) {
			if (ev.getAction() == Action.LEFT_CLICK_BLOCK)
				return false;
			
			if (!Permissions.has(player, Permissions.PLACE)) {
				player.sendMessage("§cУ тебя нет прав на установку командных блоков (commandblop.place или commandblop.*).");
				ev.setCancelled(true);
				return false;
			}
			
			// resolve clicked block to point of creation
			Block clicked = ev.getClickedBlock();
			if (clicked == null)
				return false;
			
			Location loc = clicked.getLocation().add(ev.getBlockFace().getDirection());
			Block block = loc.getBlock();
			ItemStack item = player.getInventory().getItemInMainHand();


			if (!canPlayerModifyRegion(player, block.getLocation())) {
				player.sendMessage("Здесь нельзя ставить командный блок.");
				ev.setCancelled(true);
				return false;
			}

			// place block via api
			block.setType(type);
			
			// find out which direction player is facing
			BlockFace face = getBlockFaceFromVector(player.getLocation().getDirection());
			
			Directional directional = (Directional) block.getBlockData();
			directional.setFacing(face.getOppositeFace()); // command blocks are inverted for some reason
			block.setBlockData(directional);

			if (item != null && item.hasItemMeta()) {
				CommandBlock commandBlock = (CommandBlock) block.getState();
				if (commandBlock != null) {
					String displayName = item.getItemMeta().getDisplayName();
					if (displayName != null && !displayName.isEmpty()) {
						commandBlock.setName(displayName);
						commandBlock.update();
					}
				}
			}
			
			return true;
		}
		
		return false;
	}

	private boolean canPlayerModifyRegion(Player player, Location location) {
		// WorldGuard опционален: без него защита регионов не применяется
		if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null)
			return true;
		
		try {
			RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
			RegionQuery query = container.createQuery();

			LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);

			com.sk89q.worldedit.util.Location worldGuardLocation = BukkitAdapter.adapt(location);

			ApplicableRegionSet set = query.getApplicableRegions(worldGuardLocation);
			return set.testState(localPlayer, Flags.BUILD);
		} catch (Throwable t) {
			// несовместимая версия WorldGuard не должна молча ломать установку/ломку блоков
			log.warn("WorldGuard integration failed, allowing modification: {}", t.toString());
			return true;
		}
	}


	private void sendCommandBlockTileData(Player player, CommandBlock commandBlock) {
		// CraftBlockEntityState.getUpdatePacket(Location) сам строит пакет TILE_ENTITY_DATA:
		// с правильным типом блок-сущности и полным NBT (имя, LastOutput и т.п.).
		// Сигнатура проверена по исходникам ядра: CraftBlockEntityState#getUpdatePacket(Location)
		try {
			Object state = commandBlock;
			Method updatePacket = state.getClass().getMethod("getUpdatePacket", Location.class);
			Object packetHandle = updatePacket.invoke(state, commandBlock.getLocation());
			if (packetHandle != null)
				protocolManager.sendServerPacket(player, new PacketContainer(PacketType.Play.Server.TILE_ENTITY_DATA, packetHandle));
		} catch (ReflectiveOperationException e) {
			// падение здесь не должно ронять обработчик события ("Could not pass event")
			log.error("failed to send command block data to {} at {}", player, commandBlock.getLocation(), e);
		}
	}
	
	/**
	 * Рассылает свежие данные блок-сущности всем игрокам в радиусе прогрузки, чтобы их
	 * локальные копии соответствовали серверным. Без этого клиент открывает GUI командного
	 * блока с устаревшими данными (пустой командой).
	 */
	private void broadcastCommandBlockTileData(Block block) {
		if (!(block.getState() instanceof CommandBlock state))
			return;
		
		int blockDist = Bukkit.getViewDistance() * 16;
		double rangeSq = (double) blockDist * blockDist;
		Location loc = block.getLocation();
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (player.getWorld().equals(block.getWorld())
					&& player.getLocation().distanceSquared(loc) <= rangeSq) {
				sendCommandBlockTileData(player, state);
			}
		}
	}
	
	private static BlockFace getBlockFaceFromVector(Vector vec) {
		double x = Math.abs(vec.getX());
		double y = Math.abs(vec.getY());
		double z = Math.abs(vec.getZ());
		
		if (x > z) {
			if (x > y) {
				// x largest
				return decideFacingFromValue(vec.getX(), BlockFace.EAST, BlockFace.WEST);
			} else {
				// y largest
				return decideFacingFromValue(vec.getY(), BlockFace.UP, BlockFace.DOWN);
			}
		} else {
			if (z > y) {
				// z largest
				return decideFacingFromValue(vec.getZ(), BlockFace.SOUTH, BlockFace.NORTH);
			} else {
				// y largest
				return decideFacingFromValue(vec.getY(), BlockFace.UP, BlockFace.DOWN);
			}
		}
	}
	
	private static BlockFace decideFacingFromValue(double val, BlockFace positive, BlockFace negative) {
		return val > 0 ? positive : negative;
	}
}
