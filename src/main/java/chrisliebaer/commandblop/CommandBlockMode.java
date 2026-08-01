package chrisliebaer.commandblop;

import org.bukkit.Material;

import java.lang.reflect.Field;

/**
 * Разбор режима командного блока из NMS-пакета SET_COMMAND_BLOCK.
 * Вынесено из CommandBlOp, чтобы логику можно было покрыть юнит-тестами без сервера.
 */
final class CommandBlockMode {
	
	private CommandBlockMode() {}
	
	/**
	 * Режим командного блока лежит в enum-поле пакета; имя класса поля меняется между версиями
	 * (TileEntityCommand$Type в 1.16, CommandBlockEntity$Mode в 1.20+), поэтому ищем по константам.
	 */
	static String fromPacketHandle(Object handle) {
		Class<?> clazz = handle.getClass();
		
		for (Field field : clazz.getDeclaredFields()) {
			if (!field.getType().isEnum())
				continue;
			field.setAccessible(true);
			try {
				Object o = field.get(handle); // this is an enum. i hope
				String mode = o.toString();
				if (mode.equals("REDSTONE") || mode.equals("SEQUENCE") || mode.equals("AUTO"))
					return mode;
			} catch (IllegalAccessException e) {
				throw new RuntimeException("failed to access command block mode field", e);
			}
		}
		throw new RuntimeException("failed to locate command block mode, this likely means your version is incompatible");
	}
	
	static Material toMaterial(String mode) {
		switch (mode) {
			case "REDSTONE":
				return Material.COMMAND_BLOCK;
			case "SEQUENCE":
				return Material.CHAIN_COMMAND_BLOCK;
			case "AUTO":
				return Material.REPEATING_COMMAND_BLOCK;
			default:
				throw new RuntimeException("unknown command block type: " + mode);
		}
	}
}
